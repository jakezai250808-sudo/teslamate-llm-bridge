# plays-incubator

更多玩法——后续 PR 逐步加入主线 `plays/`。

这里的玩法已经通过了初步验证，但**不会被 bridge 默认加载**（PlayRegistry 只扫 classpath `plays/` 和可选的 `PLAYS_DIR`，不扫 `plays-incubator/`）。

| 玩法 | 说明 | 状态 |
|---|---|---|
| `charging-procrastinator` | 充电拖延症——你放电放到多低才肯插枪（JSON only） | 候选，待 PR |
| `early-bird` | 早鸟指数——清晨时段出车比例 | 候选，待 PR |

## 如何将一个玩法升入主线

1. 确保玩法通过 `python3 tools/validate_plays.py` 全部 PASS（在 repo 根运行）。
2. 将玩法目录从 `plays-incubator/<name>/` 移动到 `plays/<name>/`。
3. 按 `AGENTS.md` Step 9 格式提 PR（`play: <name> — <一句话描述>`）。
4. CI 会自动校验 JSON Schema + 引擎镜像 + Java fixture runner。
