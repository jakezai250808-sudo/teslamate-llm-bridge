# plays-incubator

更多玩法——后续 PR 逐步加入主线 `plays/`。

这里的玩法已经通过了初步验证，但**不会被 bridge 默认加载**（PlayRegistry 只扫 classpath `plays/` 和可选的 `PLAYS_DIR`，不扫 `plays-incubator/`）。

_当前没有待升入的候选玩法——之前的候选（monthly-wrapped / night-owl / early-bird / charging-procrastinator）都已升入主线 `plays/`。_

新玩法可以先放这里孵化（带 `play.yaml` + `fixtures.yaml`），通过初步验证后再按下方流程升入主线。

## 如何将一个玩法升入主线

1. 确保玩法通过 `python3 tools/validate_plays.py` 全部 PASS（在 repo 根运行）。
2. 将玩法目录从 `plays-incubator/<name>/` 移动到 `plays/<name>/`。
3. 按 `AGENTS.md` Step 9 格式提 PR（`play: <name> — <一句话描述>`）。
4. CI 会自动校验 JSON Schema + 引擎镜像 + Java fixture runner。
