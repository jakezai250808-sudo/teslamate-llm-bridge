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
├── play.yaml          # required
├── creative-prompt.md # optional — image generation template
└── fixtures.yaml      # required — PRs without fixtures are rejected
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

## Step 6 — creative-prompt.md (optional)

Skip for v1 of your play unless asked (JSON-only is perfectly fine for a first PR).

**`creative-prompt.md`** — if your play has interesting output for users to want a social image,
add a `plays/<name>/creative-prompt.md` alongside `play.yaml`. It should contain a
fill-in-the-blanks prompt template (using `{placeholder}` notation matching your output field
names) that users (or Agents) can paste into GPT Image / 豆包 / Qwen-Image / Seedream after
getting the JSON. See `plays/driving-personality/creative-prompt.md` as the reference — it
contains a v1 (universal) and v2 (Seedream-tuned) template with 5-round tested phrasing rules.

This file is read by the MCP `get_creative_prompt` tool and is not required; it does not affect CI.

> **Note:** `card.svg.tmpl` is not part of the open-source play format. SVG rendering has been
> removed from the open-source path; image generation is handled via Interface 2 (see below).

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
- [ ] creative-prompt.md (if included): `{placeholders}` match output field names, v1/v2 templates present
```

One play per PR. Do not modify other plays, the schema, or the spec in the same PR.

---

## Interface 2 — Image Generation Guide (接口二：生图使用手册)

> **你持有什么决定走哪条路。** 先看决策树，再看对应路径的详细步骤。

### 快速决策树

```
你有 ChatGPT Plus / Codex 订阅？
  是 → 路径 C（平台自带，零配置）
  否 → 你有火山方舟 ARK_API_KEY？
         是 → 路径 A（API 直调，MCP 一键）
         否 → 你有豆包 / 即梦账号？
                是 → 路径 B（浏览器驱动）
                否 → 注册火山方舟（5 分钟，200 张免费）→ 路径 A
                     或请有 ChatGPT 订阅的朋友帮生图
                     都没有 → JSON 结构化结论本身即可使用，图是增强层
```

---

### 路径 A — API 直调（火山方舟 Seedream-4.0）

**适用：** 有 `ARK_API_KEY`（火山方舟 API Key）的玩家，走 MCP `generate_play_image` 一键生图。

**配置步骤（约 5 分钟）：**

1. 注册火山方舟控制台：https://console.volcengine.com/ark
2. 「开通管理」→ 搜索「Doubao-Seedream-4.0」→「立即开通」（新用户 200 张免费，之后 ¥0.2/张）
3. 「API Key 管理」→「新建 API Key」→ 复制 key
4. 在 Claude Desktop MCP 配置的 `env` 块里加：
   ```json
   "ARK_API_KEY": "your-ark-api-key-here",
   "SEEDREAM_MODEL": "doubao-seedream-4-0-250828"
   ```
5. 重启 Claude Desktop

**完整生图流程（Agent 按序执行 5 步）：**

```
步骤 1 → list_plays()
         返回：[{name: "driving-personality", ...}, ...]

步骤 2 → run_play(play_name="driving-personality", car_id=99)   ← demo 用 99；实际 TeslaMate 用你自己的 car_id
         返回：{code: "FNLE", persona: {name: "午夜高速战神", tag: "#服务区VIP年卡用户"},
                vigor: 78, night_pct: 17, avg_drive_km: 22.6, freq_pct: 83, drive_count: 52, ...}

步骤 3 → get_creative_prompt(play_name="driving-personality")
         返回：creative-prompt.md 原文（含 v1 通用版 + v2 Seedream 专用版模板）

步骤 4 → 填充占位符（Agent 自行用步骤2数据填步骤3模板的 {占位符}）
         · 选 v2 模板（Seedream 专用，5轮迭代 9/10 评分验证）
         · 必须用模板，裸写 prompt 质量显著下降

步骤 5 → generate_play_image(prompt="<填好的完整 prompt>", size="1080x1920")
         返回：base64 PNG 图片（竖版 1080×1920 海报）
```

> **为什么不能跳过步骤 3/4 直接写 prompt？** v2 模板内嵌了 Seedream 专用的文字白名单约束、
> 元语言翻译、量表布局规则——这些是 5 轮迭代的产物，裸写会丢失所有调优，中文文字乱入率显著上升。

**对话示例：**
```
用户：给我做张驾驶人格分享图
Agent：[自动串联上面 5 步] → 返回竖版海报图片
```

---

### 路径 B — 浏览器驱动（ChatGPT 网页 / 豆包网页）

**适用：** 你有 ChatGPT / 豆包已登录浏览器，Agent 用自己的浏览器自动化 MCP 帮你操作网页生图。

> **路径 B 需要「能模拟真实键盘输入」的浏览器自动化 MCP（如 Claude-in-Chrome、Playwright MCP），不能用只会注入 JS 的工具——ChatGPT 的输入框拒绝合成事件注入，实测证伪（见下方说明框）。**

> **前置说明：** 登录态由用户保证（本项目不负责登录）。

---

> **⚠ 为什么不能用纯 JS 注入（三条实测证伪）**
>
> 1. **发送按钮不渲染**：用 `nativeInputValueSetter + dispatchEvent('input')` 或 `execCommand('insertText')` 把文字写进输入框——字是进去了，但 ChatGPT 底层 React 监听的是 trusted 事件，合成事件不触发状态更新，**发送按钮始终不出现**，无法发出请求。
> 2. **Control Chrome 的 `execute_javascript` 不 await Promise**：异步 API（如 `navigator.clipboard.writeText()`）返回的 Promise 在工具层面无法等待，异步取值拿到的是 `undefined` 而非实际结果。
> 3. **ChatGPT 发送依赖 trusted user gesture**：浏览器安全模型将脚本注入的事件标记为 `isTrusted=false`，部分关键操作（发送、文件上传）只响应真实用户操作，合成点击无效。

---

**Agent 执行步骤清单（以 ChatGPT 为例，豆包同理）：**

```
步骤 1 — 准备数据与 prompt
   · run_play(play_name="driving-personality", car_id=<实际 car_id>)
   · get_creative_prompt(play_name="driving-personality")
   · 用步骤 2 数据填充 v1 模板（ChatGPT 用 v1；豆包用 v2 Seedream 专用版）
   · 得到完整 prompt 字符串，备用

步骤 2 — 连接浏览器自动化 MCP，取/建隔离 tab
   · 确认 Claude-in-Chrome（或 Playwright MCP）已连接：
     list_connected_browsers / 同等指令
   · 取一个隔离的自动化 tab，不抢用户正在用的 tab：
     tabs_context_mcp(createIfEmpty=true)
     （此调用新建独立 tab，用户原 active tab 保持不变）
   · 失败分支：MCP 未连接 → 告知用户「请先安装并连接 Claude-in-Chrome 浏览器扩展，连接后回复我」

步骤 3 — 导航到 ChatGPT，截图确认已登录
   · navigate(url="https://chatgpt.com/")
   · screenshot → 视觉确认：页面有 composer 输入框（「有问题，尽管问」或「生成图片」按钮可见）
   · 未登录（看到登录页）→ 告知用户「请先在浏览器里登录 ChatGPT，登录完成后回复我」，
     等用户回复后从本步骤重试，不要自动轮询

步骤 4 — 真实键入 prompt 并发送（关键：必须用真实键盘输入）
   · left_click 点击 composer 输入框（视觉定位，无需写死 selector）
   · type(text="<步骤1得到的完整 prompt>")
     ↑ 这是真实 OS 键盘输入，React 能感知，发送按钮随之渲染出来
   · key("Return") 发送
   · 失败分支：发送后截图未见新消息出现 → 重试一次；
     输入框找不到 → screenshot 重新视觉定位后再点击

步骤 5 — 等待生图完成（超时上限 120 秒）
   · 用 `wait(seconds=30)` 间隔（不要循环 screenshot 不等待）；
     等待后 screenshot 一次确认进度；重复直至完成或超时
   · 注意：图渲染在对话下方，**首屏可能看不到**；
     若截图未见生成图，用 `key("End")` 滚到页面底部后再 screenshot
     「正在润饰细节」占位图 → 最终完整图
   · 120 秒超时 → 告知用户「生图可能需要更长时间，请在浏览器里等待完成后回复我」
   · ChatGPT 弹出每日限额提示 → 告知用户并结束，建议切路径 A（火山方舟）

步骤 6 — 取图存盘（两种方式，按工具能力选）
   方式 a（截图法，最稳）：
     · scroll 把图卡滚进视野
     · screenshot(save_to_disk=true) 或 zoom 截取该区域
     · 屏幕分辨率足够用于社交分享
   方式 b（原图法，质量更高，但有限制）：
     · 图片 src 是 chatgpt.com/backend-api/estuary/content?...&sig=...
       需要 ChatGPT 会话 cookie，curl 直取会返回 JSON 错误
     · 只能在页内用 fetch(src)→blob→base64 取，
       但 1024×1536 PNG 的 base64 约 3 MB，**禁止整段拉进 agent 上下文**（会爆 token）
       → 必须落盘（写文件）或分块处理
   失败分支：页内 fetch 被 CORS 拦 → 退回方式 a 截图法

步骤 7 — 返回本地图片路径给用户
   · 附上生图所用的 play_name 和 prompt 版本，方便复现
```

**豆包网页同理：** 导航到 https://www.doubao.com，找生图 bot（如「豆包·图像生成」）。
composer 位置不同但同样需要真实 `type` 键入，`key Return` 发送；底座是 Seedream，prompt 选 v2 模板。

---

### 路径 C — Agent 平台自带（ChatGPT / 豆包对话里直接生图）

**适用：** 你在 ChatGPT 对话里，或在接了本项目 Coze 插件的豆包 bot 里。

用法最简单——平台本身有生图能力，对话里直接说：

```
「帮我生成驾驶人格分享图」
```

Agent 会自动调 `runPlay` 拿数据 → 填 creative-prompt v1 模板 → 触发平台内置 GPT Image / Seedream 生图。
整个流程在同一个对话窗口完成，无需任何额外配置。

---

### gallery 示例图

真实生成图在 `docs/gallery/`：

| 文件 | 内容 | 生成方式 |
|---|---|---|
| `monthly-wrapped-demo.png` | demo 数据月报卡（FNLE 午夜高速战神，30 天 1222km） | 路径 A（Seedream 4.0） |
| `driving-personality-fnle.png` | 人格卡 FNLE「午夜高速战神」 | 路径 A（Seedream 4.0） |

完整生图路径说明：[`docs/image-generation.md`](docs/image-generation.md)

在 Codex CLI 里接入 MCP（含 `user cancelled` / seatbelt 封网排障）：[`docs/connect-codex.md`](docs/connect-codex.md)
