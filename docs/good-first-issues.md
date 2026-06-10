# Good first issues (drafts)

Ready-to-file issue drafts for maintainers. When the repo goes public, create one GitHub issue
per section below:

1. Copy the section body verbatim as the issue description.
2. Set the title exactly as written in the `##` heading.
3. Apply labels: `good first issue` + the extra label(s) noted in each section.
4. Leave the issue open — contributors claim by commenting.

Estimated difficulty: **1** (play proposals, ~2–4 h) · **3** (engine work, 1–2 days).

Contributors: if an issue has not been filed yet, open a PR referencing this file and ping a
maintainer in the PR description.

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

## 3. Add `creative-prompt.md` to plays that are missing it

Labels: `good first issue`, `play`

### Problem

Several plays (e.g. `efficiency-report`, `extremes-card`, `weekend-warrior`) have rich structured
output but no `creative-prompt.md`. Users on Path A (Seedream API) or Path B (browser-driven
ChatGPT) must write their own prompt from scratch.

### Proposal sketch (to be refined in the issue)

- Add a `creative-prompt.md` to one missing play following the v1/v2 template convention in
  `docs/play-manifest-spec.md §7`.
- The v1 section must be model-agnostic (works with DALL-E 3, Gemini, Seedream).
- The v2 section should add Seedream 4.0 hints: negative prompts, Chinese flair, aspect ratio.
- All `{placeholder}` names must match the play's `output.fields` list exactly.
- Include one example generated image (PNG ≤ 2 MB) in `docs/gallery/` using demo data.

### Acceptance

- `creative-prompt.md` added next to the manifest
- `{placeholder}` names verified against `output.fields`
- Gallery image using demo data only (no real VIN, no real GPS)
- PR title format: `play(<name>): add creative-prompt.md`
