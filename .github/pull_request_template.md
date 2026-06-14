<!--
Thanks for contributing! 中英文皆可。
First-time contributors: sign the CLA by commenting the exact line in CLA.md
when the CLA Assistant bot asks. 一次性，之后自动通过。
-->

## What & why / 做了什么、为什么

<!-- One or two sentences. What does this change do, and why? 一两句话说清楚。 -->

## Type / 类型 (check one 勾一个)

- [ ] `play` — new or changed play (pure YAML under `plays/`)
- [ ] `engine` — core engine (`bridge/src/main/java/io/teslabridge/play/`) — needs test coverage + design issue first
- [ ] `ci` / `release` / `ops`
- [ ] `docs`
- [ ] `fix` / `chore`

## Checklist / 检查清单

**All PRs:**
- [ ] PR title uses conventional commits (`play:` / `fix(engine):` / `docs:` / `ci:` …)
- [ ] One PR = one concern (don't mix engine changes with play content)
- [ ] CLA signed (or will sign when the bot asks)

**If this is a play (`play:`):**
- [ ] `python3 tools/validate_plays.py` → all PASS (run from repo root, no Docker needed)
- [ ] Play passes `plays/play.schema.json` (draft 2020-12) — don't edit the schema
- [ ] `fixtures.yaml` covers the level branches (+ edge cases), values hand-computed and shown in comments
- [ ] SQL is read-only (no INSERT/UPDATE/DELETE/DROP/TRUNCATE/EXECUTE) — `PlaySqlGuard` checks this
- [ ] Sample output / screenshot attached if there's a share card

**If this touches the engine (`engine`):**
- [ ] Discussed in an issue first
- [ ] `mvn test` green (JUnit 5 + Testcontainers)
- [ ] No new dependency on SaaS-only interfaces (`CarWhitelistProvider`, `PlayAuditLogger`, `PlayScopeChecker`)

## Notes for reviewers / 给 reviewer 的备注

<!-- Anything non-obvious. 截图、计算过程、设计取舍放这。 -->
