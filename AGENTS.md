# AGENTS.md — how to add a play (written for AI coding agents)

## 30-second TL;DR

```bash
# 1. 新建文件夹
mkdir plays/<your-play-name>

# 2. 写 plays/<your-play-name>/play.yaml  （参照下面 9 步）
# 3. 写 plays/<your-play-name>/fixtures.yaml  （至少 2 个 level 分支 + 1 个 expect_unscored）

# 4. 本地验证（与 CI 完全等价）
# macOS Homebrew Python 3.12+ 是 externally-managed，直接 pip install 会报错。
# 任选一种方式安装 pyyaml：
#   方式 A（推荐 sandbox / CI agent）：
pip install --break-system-packages pyyaml
#   方式 B（不想污染系统）：
#   python3 -m venv /tmp/play-venv && source /tmp/play-venv/bin/activate && pip install pyyaml
python3 tools/validate_plays.py          # 必须全部 PASS

# 5. schema 验证
# 需要 yq（brew install yq）；也可用纯 Python 替代：
#   python3 -c "import sys,json; import yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))" \
#     plays/<your-play-name>/play.yaml > /tmp/play.json
yq -o=json '.' plays/<your-play-name>/play.yaml > /tmp/play.json
npx --yes ajv-cli@5 validate --spec=draft2020 -s plays/play.schema.json -d /tmp/play.json

# 6. 提 PR
# Title: play: <name> — <一句话描述>
```

**不需要改任何 Java 或 Python。** 如果你认为引擎需要改动，停下来开 issue。

---

You are probably reading this because a human said something like *"following AGENTS.md, add a
play that measures X"*. This file is the complete recipe. The normative spec is
[`docs/play-manifest-spec.md`](docs/play-manifest-spec.md); the machine-readable schema is
[`plays/play.schema.json`](plays/play.schema.json). Read both before writing anything.

## What you are building

One new folder:

```
plays/<your-play-name>/
├── play.yaml        # required
├── card.svg.tmpl    # only if the play has a share card
└── fixtures.yaml    # required — PRs without fixtures are rejected
```

No Java, no Python, no engine changes. If the idea seems to require engine changes (new SQL bind
params, new expr functions, multi-row results), **stop and open an issue instead** — do not invent
manifest extensions; `additionalProperties: false` will reject them anyway.

## Step 1 — name and concept

- `name`: kebab-case, 3–40 chars, matches `^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$`, equals the folder name.
- Concept test: can you state the output in one sentence ("share of drives started at night,
  as a 0–100 score with a persona")? If not, narrow it.
- Check `plays/` and open PRs for duplicates. The current built-in play is `driving-personality`
  (16-type code from vigor/night/radius/frequency axes with PNG card) — use it as your reference.

```bash
# 检查名字是否已被占用
ls plays/
# 确认 name 格式合法（应无输出 = 合法）
echo "my-play-name" | grep -vE '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$' && echo "NAME INVALID"
```

## Step 2 — write the SQL

Hard rules (the engine's static guard rejects violations at load time; all checks run on the
comment-stripped SQL — a `:car_id` that only lives in a comment does not count):

- Single statement, starts with `SELECT` or `WITH`, **no `;` anywhere**.
- Must contain a **real tenant-filter comparison `car_id = :car_id`** (table-qualified
  `d.car_id = :car_id` is fine). Merely referencing `:car_id` — projected column, comment,
  or the trivially-true `:car_id = :car_id` — is rejected.
- Word-boundary blacklist: `INSERT UPDATE DELETE DROP ALTER CREATE TRUNCATE GRANT COPY DO CALL pg_sleep`.
- Return **exactly one aggregation row**; only row 1 is consumed. 5s timeout, read-only transaction.
- Only these bind params exist: `:car_id`, `:tz` (constant `Asia/Shanghai`), `:start`, `:end`.
  Window your data with `start_date >= :start AND start_date < :end`.
- Local-time bucketing: `start_date AT TIME ZONE 'UTC' AT TIME ZONE :tz` (TeslaMate stores UTC).
- `COALESCE` aggregates that can be NULL on empty input — compute treats columns as numbers.
  - `COUNT(*)` 在空结果集时自动返回 `0`，**不需要 COALESCE 包裹**。
  - `SUM(x)` / `AVG(x)` / `MAX(x)` 在空输入时返回 `NULL`，**必须 COALESCE**，例如 `COALESCE(SUM(distance), 0)`。
  - `COUNT(*) FILTER (WHERE ...)` 行为同 `COUNT(*)`，空集也返回 `0`，不需要 COALESCE。

TeslaMate tables you may read (v1 scope):

| Table | Useful columns |
|---|---|
| `drives` | `car_id, start_date, end_date, distance, duration_min, speed_max, power_max` |
| `charging_processes` | `car_id, start_date, start_battery_level, end_battery_level, charge_energy_added, duration_min` |
| `positions` | `car_id, date, latitude, longitude, elevation, battery_level` (large table — aggregate hard, filter by `:start`/`:end`) |

## Step 3 — `min_sample`

Pick the row count column from your SQL (`total_drives`, `total_charges`, …) and a minimum that
makes the play statistically non-embarrassing (5 drives, 3 charges — that magnitude).

```yaml
# plays/<name>/play.yaml  — min_sample 示例
min_sample:
  field: total_drives   # must match a column alias returned by your SQL
  min: 5
```

## Step 4 — compute pipeline

Ordered steps; each is exactly **one** of `expr` / `level` / `template` / `lookup`. Key constraints
(full semantics in spec §4):

- `expr` is arithmetic only: `+ - * / ( )`, numeric literals, identifiers, and exactly
  `GREATEST(a,b)`, `LEAST(a,b)`, `ROUND(x)` (half-up to integer). No comparisons, no strings,
  no conditionals — conditional logic goes in `level`.
- Division by zero yields `0` + WARN; still guard denominators with `GREATEST(x, 1)`.
- Clamp scores into 0–100 with `LEAST(GREATEST(...), 100)`-style expressions.
- `level` thresholds are checked top-down with strict `<`; the **last entry must omit `lt`**
  (catch-all), or out-of-range inputs kill the play at load time.
- `lookup` needs a matching top-level `tables:` entry; keys must cover every `level` label you
  feed it, and `default:` is **required** (schema + engine both reject lookups without it).
  Leaf values: strings ≤ 120 chars.
  `default` 是一个**对象**，里面的 key 由你自己的 lookup table 结构决定——没有引擎强制的固定字段，
  只需与 table 里每个 label entry 的结构一致（通常含 `name`、`color`、`tagline` 等）。
  如不确定，参考 `plays/driving-personality/play.yaml` 的 `tables:` + `default:` 写法。
- `template`: `${ident}` substitution only; `window_days` is the only built-in available here —
  `car_name` / `car_model` / `window_label` / `generated_at` are **card-only** (spec §5) and
  referencing them in a compute template fails validation.

Copywriting: this repo's primary audience is Chinese Tesla owners — Chinese labels/summaries are
preferred (see the two reference plays), English `description` for the LLM platforms. Keep labels
funny but kind; self-deprecation good, shaming bad.

```yaml
# plays/<name>/play.yaml  — compute 段落最小骨架
compute:
  - var: ratio
    expr: "ROUND(night_drives * 100 / GREATEST(total_drives, 1))"   # expr: 仅四则运算

  - var: level_label
    level:
      input: ratio
      thresholds:
        - { lt: 20,  label: "白天模范生" }
        - { lt: 60,  label: "偶尔夜驾" }
        - { label:   "终极夜猫子" }         # 最后一项必须省略 lt（catch-all）

  - var: summary
    template: "近 ${window_days} 天夜驾比例 ${ratio}%，人格：${level_label}"
```

## Step 5 — output fields

List only what platforms should see: `{ name, from, type }`, `type ∈ number|string|object`.
Include the headline score, the label, the summary string, and the raw counts that make the
number explainable.

```yaml
# plays/<name>/play.yaml  — output 段落示例
output:
  fields:
    - { name: ratio,       from: ratio,       type: number }
    - { name: level_label, from: level_label, type: string }
    - { name: summary,     from: summary,     type: string }
    - { name: total_drives,from: total_drives, type: number }  # 原始计数让结果可解释
```

## Step 6 — card (optional) and creative-prompt (optional)

Skip both for v1 of your play unless asked (JSON-only is perfectly fine for a first PR).

**`creative-prompt.md`** — if your play has interesting enough output for users to want a social
image, add a `plays/<name>/creative-prompt.md` alongside `play.yaml`. It should contain a
fill-in-the-blanks prompt template (using `{placeholder}` notation matching your output field
names) that users can paste into GPT Image / 豆包 / Qwen-Image after getting the JSON. See
`plays/driving-personality/creative-prompt.md` as the reference. This file is not required and
does not affect CI; it is purely for end-user documentation.

**`card.svg.tmpl`** — the engine-rendered deterministic PNG. If you do build one, copy
`plays/driving-personality/card.svg.tmpl` as a base and obey spec §7:

- `viewBox="0 0 1080 1080"`, well-formed XML, no `<script`/`<foreignObject`/`<!ENTITY`/`<!DOCTYPE`.
- Any `href`/`xlink:href`/`src` attribute and any `url(...)` reference must be a **local `#id`**
  — every external scheme (`https:`, `file:`, `data:` …) is rejected, whitespace tricks included.
- **No emoji in SVG text** (non-BMP code points fail lint — the render host has no emoji font and
  Batik cannot draw color emoji; they become tofu boxes). Emoji goes in the manifest `emoji:`
  field; draw pictograms as vector shapes.
- Every `${var}` must resolve; numbers only in geometry attributes.
- `${car_name}` is guaranteed ≤ 12 code points (engine truncates with `…`) — lay out for that.
- Set `card: { template: card.svg.tmpl }` in the manifest (that exact filename — it is locked).

## Step 7 — fixtures (this is where most PRs fail)

`fixtures.yaml` drives CI (`.github/workflows/validate-plays.yml` → `tools/validate_plays.py`):
each entry under the top-level `fixtures:` key pushes a fake SQL `row` through `min_sample` +
compute and asserts `expect`. Requirements:

- Top-level key is `fixtures:` (not `cases:` — that fails CI with a rename hint).
- ≥ 2 fixtures hitting **different** `level` branches (ideally every branch).
- ≥ 1 fixture with `expect_unscored: true` (below the `min_sample` threshold, no `expect:` block).
- Fixture entry 字段：
  - `name:` **或** `description:`（两者均可，CI 只取第一个非空作标签，不影响执行）
  - `window_days:` 可选整数，不填时引擎用 `default_days`
  - `row:` 必填，包含所有 SQL 返回列（用 `0` 填充玩法 SQL 里你没关注的列）
  - `expect:` 被评分后的预期 compute 输出（omit if `expect_unscored: true`）
  - `expect_unscored: true` 专用于低于 min_sample 的情况，与 `expect:` 互斥
- **Hand-compute every expected value from your own manifest, step by step, and write the
  arithmetic in a YAML comment** (see the reference plays). Do not guess; if your computed value
  disagrees with your intuition, your expr is wrong — fix the expr, not the fixture.

```yaml
# plays/<name>/fixtures.yaml  — 最小合规骨架
fixtures:
  - description: "数据不足（低于 min_sample）"
    row:
      total_drives: 2    # < min_sample=5
      night_drives: 1
    expect_unscored: true  # 不带 expect: 块

  - description: "低夜驾（白天模范生分支）"
    row:
      total_drives: 10
      night_drives: 1
      # ratio = ROUND(1 * 100 / GREATEST(10, 1)) = ROUND(10.0) = 10 → lt:20 → "白天模范生"
    expect:
      ratio: 10
      level_label: "白天模范生"

  - description: "高夜驾（终极夜猫子 catch-all 分支）"
    row:
      total_drives: 10
      night_drives: 8
      # ratio = ROUND(8 * 100 / 10) = 80 → ≥60 → catch-all → "终极夜猫子"
    expect:
      ratio: 80
      level_label: "终极夜猫子"
```

## Step 8 — self-validate before the PR

Run exactly what CI runs:

```bash
# 1. engine-mirror validation + fixture execution (SQL guard, compute rules,
#    card lint, every fixture case through the compute pipeline)

# macOS Homebrew Python 3.12+ 是 externally-managed environment，直接 pip install 报错。
# 方式 A（最快，适合 CI / sandbox agent，不介意系统包）：
pip install --break-system-packages pyyaml
# 方式 B（干净环境）：
# python3 -m venv /tmp/play-venv && source /tmp/play-venv/bin/activate && pip install pyyaml

python3 tools/validate_plays.py

# 2. schema validation (any draft 2020-12 validator works)
# 需要 yq。若未安装（brew install yq），用纯 Python 替代：
# python3 -c "import sys,json; import yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))" \
#   plays/<name>/play.yaml > /tmp/play.json
yq -o=json '.' plays/<name>/play.yaml > /tmp/play.json
npx --yes ajv-cli@5 validate --spec=draft2020 -s plays/play.schema.json -d /tmp/play.json

# 3. quick greps if you want extra confidence (validate_plays.py already covers these)
# (yq 可用上方 Python 替代输出同等内容)
grep -nE ';|\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|COPY|DO|CALL|pg_sleep)\b' \
  <(yq '.sql' plays/<name>/play.yaml) && echo "GUARD VIOLATION" || echo "sql guard ok"
grep -qE 'car_id\s*=\s*:car_id' <(yq '.sql' plays/<name>/play.yaml) && echo "tenant filter ok"
```

Then re-verify every fixture expectation by hand one more time.

## 本地完整验证一条龙

在提 PR 之前，依次跑完以下命令——全部通过才算可提交：

```bash
PLAY=<your-play-name>

# 1. 引擎镜像验证 + 所有 fixture 执行（与 CI validate-plays.yml 完全等价）
# macOS externally-managed Python 需加 --break-system-packages，或先建 venv：
#   python3 -m venv /tmp/play-venv && source /tmp/play-venv/bin/activate
pip install --break-system-packages pyyaml   # 首次安装
python3 tools/validate_plays.py

# 2. JSON Schema 验证（draft 2020-12）
# 若无 yq（brew install yq），用纯 Python 替代：
#   python3 -c "import sys,json,yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))" \
#     plays/$PLAY/play.yaml > /tmp/play.json
yq -o=json '.' plays/$PLAY/play.yaml > /tmp/play.json
npx --yes ajv-cli@5 validate --spec=draft2020 -s plays/play.schema.json -d /tmp/play.json

# 3. SQL 快速 grep（validate_plays.py 已覆盖，可跳，但便于肉眼确认）
grep -nE ';|\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|COPY|DO|CALL|pg_sleep)\b' \
  <(yq '.sql' plays/$PLAY/play.yaml) && echo "GUARD VIOLATION" || echo "sql guard ok"
grep -qE 'car_id\s*=\s*:car_id' <(yq '.sql' plays/$PLAY/play.yaml) \
  && echo "tenant filter ok" || echo "TENANT FILTER MISSING"

# 4. 可选：对真实数据库预跑（需要本地 bridge 已启动）
curl "http://localhost:8770/api/v1/cars/1/play/$PLAY"
```

预期输出：步骤 1-3 无 ERROR；步骤 4 返回 `"scored": true` 或 `"scored": false`（样本不足）。

---

## Step 9 — open the PR

Title: `play: <name> — <one-line description>`. PR body checklist:

```markdown
- [ ] `python3 tools/validate_plays.py` passes locally
- [ ] schema validates (draft 2020-12)
- [ ] SQL: single SELECT/WITH, real `car_id = :car_id` filter, no blacklist words, one aggregation row
- [ ] level has a catch-all; lookup tables cover all labels + required default
- [ ] fixtures: all level branches + expect_unscored case, values hand-computed
- [ ] card (if any): passes lint (no emoji in text, refs local-# only), all ${vars} resolve, 1080x1080
```

One play per PR. Do not modify other plays, the schema, or the spec in the same PR.
