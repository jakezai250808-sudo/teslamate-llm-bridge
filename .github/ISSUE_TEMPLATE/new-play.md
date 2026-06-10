---
name: New play proposal / 玩法提案
about: Propose a new play before implementing it (中英文皆可)
title: "[play] <name>: <one-line idea>"
labels: play-proposal
---

## The idea / 玩法创意

<!-- One or two sentences. What does this play tell a Tesla owner about themselves?
     Self-deprecating humor welcome. 一两句话说清楚这个玩法让车主看到什么。 -->

## Data needed / 需要的数据

<!-- Which TeslaMate tables/columns? (v1 scope: drives, charging_processes, positions)
     Rough sketch of the aggregation is enough — no need for final SQL. -->

## Output sketch / 输出草图

<!-- Headline number/score? Level labels (the funny part — draft them here)?
     JSON-only or with a 1080x1080 share card? -->

- Score / 主指标:
- Levels / 等级文案:
- Card / 卡片: yes / no (JSON-only)

## Sample summary line / 一句话总结示例

<!-- e.g. "过去 30 天，你有 55% 的行程发生在深夜，夜猫子指数 97/100" -->

## Willing to implement? / 愿意自己实现吗

<!-- yes (I'll follow AGENTS.md, possibly with an AI coding agent) / no (idea only) -->

---

Before implementing, please read [`docs/play-manifest-spec.md`](../../docs/play-manifest-spec.md)
and [`AGENTS.md`](../../AGENTS.md). Plays that need engine changes (new bind params, new expr
functions, multi-row results) should stay in the issue for discussion first.
