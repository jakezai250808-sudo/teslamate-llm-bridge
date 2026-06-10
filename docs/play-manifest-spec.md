# Play Manifest Specification — v1 (frozen)

This document is the normative spec for `play.yaml`. The machine-readable schema is
[`plays/play.schema.json`](../plays/play.schema.json) (JSON Schema draft 2020-12) — when prose and
schema disagree, the schema wins for structure, this document wins for runtime semantics.

A *play* is a self-contained folder:

```
plays/<name>/
├── play.yaml           # manifest (required)
├── creative-prompt.md  # image generation template (optional)
└── fixtures.yaml       # CI test cases (required, always)
```

The engine loads plays from the application classpath and, optionally, from an external
directory (`PLAYS_DIR` env, hotfix channel — same-name plays there override classpath, with a
WARN). **A play that fails any validation step is skipped with a WARN and never blocks startup.**
The registry is immutable after startup; changing `PLAYS_DIR` requires a restart (v1, by design).

---

## 1. Top-level fields

| Field | Required | Constraints | Meaning |
|---|---|---|---|
| `schema_version` | yes | const `1` | Manifest format version. |
| `name` | yes | `^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$` | Stable ID; must equal the folder name. Goes into URLs and ETags. |
| `title` | yes | ≤ 40 chars | Human title shown to users (any language; CJK fine). |
| `emoji` | no | ≤ 8 chars | Display emoji. |
| `description` | yes | ≤ 300 chars | What the play tells the user. LLM platforms surface this as the tool description, so write it for an LLM audience. |
| `scope` | no | enum, only `read:drives` in v1 | Data-access scope. v1 has a single value covering read-only access to the TeslaMate database; finer scopes are reserved for v2. |
| `params.default_days` | no | int 1–365 | Default analysis window in days when the caller does not specify one. |
| `sql` | yes | ≤ 8000 chars | One read-only aggregation query. See §2. |
| `min_sample` | yes | `{field, min}` | Minimum sample gate. See §3. |
| `compute` | yes | array 1–64 steps | Ordered compute pipeline. See §4. |
| `tables` | no | 3-level map, leaf strings ≤ 120 chars | Named lookup tables for `lookup` steps. |
| `output` | yes | `{fields: [...]}` | Public JSON contract. See §6. |
| ~~`card`~~ | removed | — | SVG card templates have been removed from the open-source play format. Use `creative-prompt.md` instead. |

`additionalProperties: false` everywhere — unknown keys are a validation error, not a silent no-op.

## 2. SQL contract and static guard

The engine treats every manifest as **semi-trusted input** (the `PLAYS_DIR` path has weaker trust
than PR-reviewed classpath plays), so the SQL guard applies to all plays, always.

Load-time gate (any failure ⇒ play skipped). All textual checks run on the **comment-stripped**
SQL — a `:car_id` that only appears inside a `--` or `/* */` comment does not count:

- Must contain a real tenant-filter comparison `car_id = :car_id` (table-qualified
  `t.car_id = :car_id` is fine; whitespace around `=` is fine). Merely *referencing* `:car_id` —
  as a projected column, inside a comment, or as the trivially-true `:car_id = :car_id` — is
  rejected: without a real filter the query would aggregate **every owner's data**.
- After comment stripping, must start with `SELECT` or `WITH`.
- Must not contain `;` (anywhere, comments included).
- Word-boundary blacklist: `INSERT | UPDATE | DELETE | DROP | ALTER | CREATE | TRUNCATE | GRANT | COPY | DO | CALL | pg_sleep`.

The guard is a best-effort static gate, not a proof: it cannot verify that the filter actually
constrains every table you read (e.g. an unfiltered side table in a cartesian join). Reviewers
still check tenant-filter semantics on every play PR.

Runtime guarantees (engine-side, not your concern but good to know):

- Dedicated `JdbcTemplate` on the TeslaMate datasource with `queryTimeout = 5s` and `maxRows = 100`,
  wrapped in a **read-only transaction**.
- Your query should be an aggregation returning **exactly one row**; that row feeds the compute
  pipeline. Multi-row results beyond row 1 are not consumed in v1.

Available bind parameters (these are the only ones; arbitrary user input never reaches SQL):

| Param | Type | Value |
|---|---|---|
| `:car_id` | long | The caller's bound car. Always filter on it. |
| `:tz` | string | Constant `Asia/Shanghai` in v1 (not user input). TeslaMate time columns are **naive UTC timestamps**, so local-time bucketing requires the double conversion `(start_date AT TIME ZONE 'UTC') AT TIME ZONE :tz` — a single `AT TIME ZONE :tz` misreads the UTC value as local time and shifts every hour bucket by the UTC offset. |
| `:start` / `:end` | timestamp | Analysis window boundaries (UTC), derived from `params.default_days` or the caller's requested window. |

Useful TeslaMate tables (read-only): `drives` (start_date, end_date, distance, duration_min,
speed_max, power_max, car_id), `charging_processes` (start_date, start_battery_level,
end_battery_level, charge_energy_added, duration_min, car_id), `positions` (date, latitude,
longitude, elevation, battery_level, car_id).

## 3. `min_sample`

```yaml
min_sample: { field: total_drives, min: 5 }
```

After the query runs, the engine reads `field` from the result row. If it is below `min`, the play
returns an "insufficient sample" response instead of running the compute pipeline — this keeps
cards from rendering embarrassing nonsense for brand-new cars. Every fixtures file must include at
least one below-threshold case (§8).

## 4. The compute pipeline

`compute` is an **ordered** list. Each step binds one variable (`var`, `^[a-z_][a-z0-9_]*$`) and
must be exactly one of four kinds (`oneOf` in the schema):

### 4.1 `expr` — arithmetic

```yaml
- var: night_ratio
  expr: "ROUND(night_drives * 100 / GREATEST(total_drives, 1))"
```

The expression language is deliberately tiny (a purpose-built recursive-descent parser — **not**
SpEL, not any general EL):

- Operators: `+ - * / ( )`.
- Numeric literals (integers and decimals).
- Identifiers `[a-z_][a-z0-9_]*` referencing: SQL result columns, previously computed vars, and
  the built-in `window_days` (length of the analysis window in days).
- Exactly three functions: `GREATEST(a, b)`, `LEAST(a, b)`, `ROUND(x)` (round-half-up to integer).
- **Division by zero evaluates to `0`** and the engine logs a WARN — cards stay renderable.
  Guard denominators with `GREATEST(x, 1)` anyway; it reads better.
- No strings, no comparisons, no method calls, no conditionals — conditional logic belongs in
  `level` steps.

Column names cannot be verified at compile time (SQL columns are only known at runtime), which is
exactly why fixtures (§8) are mandatory: they execute the full pipeline in CI. At runtime an
unknown identifier fails the request with `PLAY_COMPUTE_ERROR` (HTTP 500) and an ERROR log.

### 4.2 `level` — ordered threshold mapping

```yaml
- var: owl_title
  level:
    input: owl_score
    thresholds:
      - { lt: 20, label: "早睡模范生" }
      - { lt: 45, label: "偶尔修仙党" }
      - { label: "终极夜猫子" }   # no `lt` = catch-all, put it last
```

Evaluated top-down; the **first** threshold whose `lt` is strictly greater than the input wins.
The final entry should omit `lt` to act as the catch-all — otherwise out-of-range inputs have no
label and the play fails validation at load time.

### 4.3 `template` — string interpolation

```yaml
- var: summary
  template: "过去 ${window_days} 天，你有 ${night_ratio}% 的行程发生在深夜"
```

`${ident}` references any earlier var, SQL column, or the built-in `window_days` (§5 — the four
card-only built-ins are **not** available here). ≤ 200 chars. Pure concatenation — no expressions
inside `${}`.

### 4.4 `lookup` — keyed table lookup

```yaml
- var: persona
  lookup:
    key: owl_title          # var whose *string value* is the lookup key
    table: personas         # name under top-level `tables:`
    default:                # REQUIRED in practice: guarantees total coverage
      name: "夜行者"
      color: "#7c6ff0"
tables:
  personas:
    "早睡模范生": { name: "早睡模范生", color: "#5ec26a" }
```

Produces an *object* var (string fields only, each ≤ 120 chars). Templates and card templates can
reach into it with dot paths: `${persona.color}`.

## 5. Built-in variables

| Name | Where | Value |
|---|---|---|
| `window_days` | expr / template / card | analysis window length (number) |
| `car_name` | **card only** | user's car display name, truncated by the engine to **≤ 12 code points** (+ `…` when longer) — design your card for that width |
| `car_model` | **card only** | e.g. `Model 3` |
| `window_label` | **card only** | human-readable window, e.g. `近 30 天` |
| `generated_at` | **card only** | render timestamp (local tz) |

Only `window_days` exists in the compute-pipeline context. The other four are injected by the
card-rendering path *after* compute has finished — referencing them in a compute `template` step
is rejected at load time (and would otherwise fail every request with `PLAY_COMPUTE_ERROR`).

## 6. `output.fields`

The public JSON contract — only listed fields are returned to the platform:

```yaml
output:
  fields:
    - { name: night_ratio, from: night_ratio, type: number }
    - { name: persona,     from: persona,     type: object }
```

`from` references a compute var or SQL column; `type` ∈ `number | string | object`. `type` is
enforced at render time — notably, a `number` field injected into a card template's geometry
attribute (e.g. a bar `width`) is validated as numeric before injection.

## 7. `creative-prompt.md` (optional)

If present, this file provides image generation templates for Interface 2. The file is read
by the `get_creative_prompt` MCP tool and served at the HTTP endpoint `GET
/api/v1/plays/{name}/creative-prompt`.

Convention: include two named sections separated by `---`:

- **v1 universal** — plain-language prompt that works with any image model (DALL-E 3, Gemini
  Imagen, 豆包 Seedream).
- **v2 Seedream-tuned** — optimised for Seedream 4.0 (火山方舟 doubao-seedream-4-0); may include
  Chinese flair text, negative prompts, aspect-ratio hints.

Use `{placeholder}` syntax (curly braces, no `$`) matching the names from the `output.fields`
list. The MCP tool substitutes actual values from `run_play` before sending the filled prompt to
the image model.

> **Note:** `card.svg.tmpl` (SVG rendering via Apache Batik) is not part of the open-source play
> format. Image generation is handled entirely through `creative-prompt.md` + Interface 2
> (see [docs/image-generation.md](image-generation.md)).

## 8. `fixtures.yaml` (required)

Fixtures make plays testable without a database: CI feeds each `row` through `min_sample` and the
compute pipeline and asserts the outputs. **A play without fixtures fails CI**
(`.github/workflows/validate-plays.yml` runs both `tools/validate_plays.py` and `mvn test` in the
`bridge/` module; the Java `PlayRegistryTest` exercises the same fixtures through the production
engine's SnakeYAML loader, SQL-guard, and compute pipeline).

To run the Java fixture suite locally: `cd bridge && mvn test`.

The format below is the one both fixture runners consume — top-level key `fixtures:`,
below-threshold cases marked with `expect_unscored: true`:

```yaml
fixtures:
  - name: heavy-night-owl
    window_days: 30   # injected as the built-in window_days (default 30)
    row:              # fake SQL result row (column -> value, lowercase)
      total_drives: 40
      night_drives: 22
      night_km: 310.5
    expect:           # asserted against compute vars / output fields
      night_ratio: 55           # numbers compared within ±0.001
      owl_title: "终极夜猫子"
      persona.color: "#4c4cff"  # dot paths reach into object vars
  - name: too-few-drives
    window_days: 30
    row: { total_drives: 3, night_drives: 1, night_km: 12.0 }
    expect_unscored: true   # min_sample gate must trip (no `expect:` block)
```

Requirements:

- ≥ 2 cases exercising different `level` branches.
- ≥ 1 case with `expect_unscored: true`.
- Expected values must be hand-computed from the manifest (that is the point).

## 9. Validation checklist (run before opening a PR)

1. YAML → JSON, validate against `plays/play.schema.json` with any draft 2020-12 validator, e.g.
   `npx --yes ajv-cli@5 validate --spec=draft2020 -s plays/play.schema.json -d <(yq -o=json '.' plays/<name>/play.yaml)`.
2. Grep your SQL for the §2 blacklist and `;`; confirm `:car_id` is present and it starts with
   `SELECT`/`WITH`.
3. Confirm every `${...}` in templates resolves (§4.3, §7).
4. Hand-verify each fixture expectation.
5. If you ship a card: check the lint blacklist (§7) and that it looks right at 1080×1080.
