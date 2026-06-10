# Good first issues (drafts)

Ready-to-file issue drafts. Maintainers: copy each section into a GitHub issue with labels
`good first issue` + the label noted. Contributors: if the issue is not filed yet, feel free to
just open a PR referencing this file.

---

## 1. Play: `weekend-warrior` — 周末战士

Labels: `good first issue`, `play-proposal`

### The idea

Are you a weekend road-tripper or a weekday commuter? Compare weekend vs weekday driving distance
over the window and award a persona. 周末里程 vs 工作日里程之比 → "周末战士 / 通勤机器 / 全天候选手"。

### Data needed

`drives` only: bucket `start_date AT TIME ZONE 'UTC' AT TIME ZONE :tz` by `EXTRACT(ISODOW FROM ...)`
(6–7 = weekend), `SUM(distance)` + `COUNT(*)` per bucket, single row via `FILTER` clauses
(see `plays-incubator/night-owl/play.yaml` for the FILTER pattern).

### Output sketch

- `weekend_km`, `weekday_km`, `weekend_ratio` (% of total km on weekends; note a *fair* baseline
  is ~28.6% = 2/7, so center the levels around that, not 50%)
- Levels (draft, improve freely): `<15` "通勤机器", `<35` "全天候选手", `<60` "周末出逃派",
  catch-all "周末战士"
- JSON-only is fine for the first PR; card optional follow-up
- Watch out: divide by `GREATEST(weekend_km + weekday_km, 1)` — new cars may have 0 km

### Acceptance

Follow `AGENTS.md` steps 1–9. Fixtures must cover all level branches + `insufficient_sample`
(suggest `min_sample: { field: total_drives, min: 8 }` so both buckets have data).

---

## 2. Play: `altitude-hunter` — 海拔猎人

Labels: `good first issue`, `play-proposal`

### The idea

The highest point your Tesla has ever reached in the window, as an achievement: max elevation,
total climb-ish flavor, and a mountaineering-grade badge. 你的车在统计窗口内到过的最高海拔 →
"平原巡航者 / 丘陵爱好者 / 盘山高手 / 高原征服者"。

### Data needed

`positions` (`elevation`, `date`, `car_id`). **This table is huge** — keep it to a single
aggregate pass filtered by `:car_id` + `date >= :start AND date < :end`:
`MAX(elevation) AS max_elevation`, `COUNT(*) FILTER (WHERE elevation >= 1000) AS high_altitude_points`,
plus a row-count column for `min_sample`. Mind NULL elevations (`COALESCE`, `IS NOT NULL`).

### Output sketch

- `max_elevation` (m), badge levels by max elevation (draft): `<200` "平原巡航者",
  `<800` "丘陵爱好者", `<2000` "盘山高手", catch-all "高原征服者"
- Summary like: "近 90 天你到过的最高点是 1860 m，盘山高手认证"
- JSON-only first; an achievement-style card is a great follow-up

### Acceptance

`AGENTS.md` steps 1–9; fixtures cover all badge branches + `insufficient_sample`.

---

## 3. Engine: configurable card themes

Labels: `good first issue`, `engine`, **blocked-until-engine-import** (the play engine lands in
this repo in the coming weeks — see README roadmap; design discussion can start now)

### Problem

Card templates hardcode their palette (see `plays/driving-personality/card.svg.tmpl`: background gradient,
text grays). Every play inventing its own colors means inconsistent share cards and makes a future
"dark/light/brand" user setting impossible.

### Proposal sketch (to be refined in the issue)

- Define a small set of theme tokens the renderer always provides as built-in template variables,
  e.g. `${theme.bg}`, `${theme.fg}`, `${theme.muted}`, `${theme.accent}` — same substitution and
  XML-escaping path as existing variables, so no new injection surface.
- Themes are engine-side named presets (start with `night` + `light`); plays may still override
  `accent` per persona (driving-personality's `${persona.color}` keeps working).
- Spec impact: extend §5 built-ins table + §7 in `docs/play-manifest-spec.md`; **no manifest
  schema change** (tokens are built-ins, not new YAML fields), so `schema_version` stays 1.
- Migrate `driving-personality`'s template to tokens as the reference implementation.

### Acceptance

- Spec PR updating built-ins (§5) and card rules (§7)
- Renderer change + tests (once engine code is in-repo)
- `driving-personality` card renders identically under the default theme
