# 生图路径指南

玩法引擎（play engine）负责从 TeslaMate 原始数据中计算分数、人格码和数据摘要，
并通过 `card.svg.tmpl` 渲染一张确定性的 SVG→PNG 保底卡片。
**生图是独立的可选增强层**——好的 AI 生图可以把一张结构化数据卡片变成小红书爆款。

本文档梳理各平台玩家的可用路径、实测质量和接入成本，重点解决
**纯 Claude Code / MCP 玩家**（Anthropic 无图像生成模型）的最优方案。

---

## 生图路径矩阵

| 玩家持有 | 生图路径 | 质量 | 中文文字能力 | 成本 | 验证状态 |
|---|---|---|---|---|---|
| ChatGPT Plus / Team（含 Codex 订阅） | **GPT Image**（DALL-E 3 / gpt-image-1） | ★★★★★ | 极强——中文字符准确，长文本稳定 | 订阅内免费，无额外费用 | 双卡实测 ✅ |
| 豆包 / 即梦用户 | **豆包生图**（Seedream 底座） | ★★★★ | 强——原生中英双语，小字布局稳定 | 免费额度（每日/每月）| 实测 ✅ |
| **中国大陆直连玩家** | **火山 Seedream-4.0（方舟 Ark API）** | ★★★★★ | 极强——中文海报、长文本、数字全准 | 新用户 200 张免费；之后 ¥0.2/张；无需 VPN | 实测 ✅ |
| 任意玩家（保底） | **SVG `renderPlayCard`**（play engine 内置） | ★★★ | 精确——数字 100% 准确，无字体缺失 | 免费，无外部依赖 | 全渠道可用 ✅ |
| 有 Gemini key + 新 GCP project | **Gemini flash image**（gemini-2.5-flash-image 等） | ★★★★ | 较好——支持中英文混排 | 新 project 免费层 ~500 RPD | 有条件可用 ⚠️（见下方注） |
| 有 DashScope 国际版账号 | **Qwen-Image-2.0**（阿里云 DashScope） | ★★★★ | 极强——当前商用 API 中文海报最强 | 新用户 100 张免费（90 天）；之后 $0.035/张 | 实测 ✅ |
| 无以上任何账号 | **Pollinations.ai**（Flux 底座） | ★★ | 差——Flux 中文文字渲染弱 | 理论无限；申请 key 后稳定 | 实测 ⚠️（中文场景不推荐） |

---

## 按玩家类型的推荐路径

### A. ChatGPT / Codex 订阅玩家（最推荐）

买了 Codex 就等于买了 ChatGPT Plus，自动拥有 GPT Image 能力。
在 ChatGPT 对话里调用 `runPlay(driving-personality)` 拿到 JSON 后，直接把
[`plays/driving-personality/creative-prompt.md`](../plays/driving-personality/creative-prompt.md)
里的模板填充后发给 ChatGPT 生图——整个流程在同一个对话窗口完成，不需要额外工具。

**中文数字全准，质量最高，订阅内零增量成本。这是目前体验最完整的路径。**

### B. 豆包玩家

在豆包 bot 里调用 play（通过 Coze 插件接入，见 [docs/connect-coze.md](connect-coze.md)），
拿到 JSON 后直接触发豆包的 Seedream 生图能力，也可以调用即梦（Jimeng）API。
Seedream 2.0 原生中英双语，对海报文字布局的处理优于 Flux 系模型。

### C. 纯 Claude Code / MCP 玩家（当前最优方案）

Anthropic 目前**没有图像生成模型**。Claude Code 本身无法生图，可选路径如下：

**推荐优先级：**

1. **火山方舟 Seedream-4.0（中国大陆用户首选）**——MCP tool `generate_play_image` 直连
   中国大陆无需 VPN，原生中文海报能力最强，新用户 200 张免费，之后 ¥0.2/张。
   配置 `ARK_API_KEY` 后，Claude Code 在 MCP 会话里直接调用 `generate_play_image` 一键生图。
   详见下方 [Seedream 配置小节](#火山方舟-seedream-40-配置)。

2. **内置 SVG 保底卡片**（`card.svg.tmpl`）——直接调用
   ```
   GET /api/v1/cars/{car_id}/play/driving-personality/card.png
   ```
   数字 100% 准确，CJK 字体内置，全平台可用，零外部依赖。
   适合"要的是数据准确、不在意插画质感"的场景。

3. **Qwen-Image-2.0（DashScope 国际版）**——需注册，新用户 100 张免费
   - 注册：https://dashscope-intl.aliyuncs.com
   - 实测中文海报文字渲染极强的商用 API
   - 见下方 [Qwen API 调用示例](#qwen-image-调用示例)

4. **请一位有 ChatGPT 订阅的朋友生图**——严肃的保底选项。
   把 play JSON + creative-prompt 模板发给对方，在 ChatGPT 里生图后发回图片。

**Gemini flash image 为何不作为纯 Claude 玩家的首选：**
2026-06-10 实测定论：**Gemini 图像模型没有免费层**。全新 GCP project +
全新 API key（零历史调用）调 `gemini-2.5-flash-image` 依然返回
`429 generate_content_free_tier_requests, limit: 0`——limit:0 是设计而非耗尽，
换 project / 换 key 无解，必须给 project 绑卡开 GCP billing（且 Google AI Pro
订阅不含 API 配额）。仅推荐给已有 GCP billing 的海外玩家；国内玩家直接用上面的
火山 Seedream（免费 200 张 + 直连）。

---

## 火山方舟 Seedream-4.0 配置

> 中国大陆直连，无需 VPN；新用户赠 200 张免费额度，之后 ¥0.2/张。
> MCP tool `generate_play_image` 已内置，配置好 `ARK_API_KEY` 即可直接调用。

### 三步开通

1. **注册方舟控制台**：https://console.volcengine.com/ark
2. **开通 Seedream-4.0 模型**：控制台左侧「开通管理」→ 搜索「Doubao-Seedream-4.0」→「立即开通」
3. **创建 API Key**：控制台「API Key 管理」→「新建 API Key」→ 复制 key

### 配置到 MCP server

在 Claude Desktop（或 Cursor / Codex）的 MCP server 配置的 `env` 块里加：

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "python3",
      "args": ["/absolute/path/to/mcp-server/server.py"],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "ARK_API_KEY": "your-ark-api-key-here",
        "SEEDREAM_MODEL": "doubao-seedream-4-0-250828"
      }
    }
  }
}
```

> **安全提示：API Key 请勿 commit 到版本库。** `claude_desktop_config.json` 存储在本地用户目录（macOS：`~/Library/Application Support/Claude/`），不要上传至 GitHub 或任何公开仓库。

`SEEDREAM_MODEL` 可省略（默认 `doubao-seedream-4-0-250828`）。

### MCP tool 用法

配置完成、重启 Claude Desktop 后，直接在对话中：

```
先跑 run_play(driving-personality, car_id=1)，拿到 JSON 后
用 creative-prompt 模板填入真实数据，再调用 generate_play_image 生成分享图。
```

Claude Code 会自动串联这两步；`generate_play_image` 接受：
- `prompt`：填好占位符的完整文生图 prompt（参考 `plays/driving-personality/creative-prompt.md`）
- `size`：输出尺寸，默认 `"1024x1024"`；竖版推荐 `"1080x1920"`；横版 `"1920x1080"`

### 常见错误排查

| 错误 | 原因 | 解决 |
|---|---|---|
| `ModelNotOpen` | 未在控制台开通 Seedream-4.0 | 「开通管理」→ 开通模型 |
| `InvalidApiKey` | Key 不正确或已过期 | 重新生成 Key，检查复制是否完整 |
| `InsufficientBalance` | 免费额度用完 | 控制台充值，¥0.2/张 |
| `RateLimitExceeded` | QPS 超限 | 稍后重试，或提交工单提升配额 |

---

## Qwen-Image 调用示例

> 新用户免费 100 张，注册 DashScope 国际版（Singapore 节点）即可。

```bash
pip install dashscope
```

```python
import dashscope
from dashscope import ImageSynthesis

dashscope.base_http_api_url = "https://dashscope-intl.aliyuncs.com/api/v1"

resp = ImageSynthesis.call(
    api_key="<你的 DashScope key>",
    model="qwen-image-2.0",          # 或 qwen-image-2.0-pro（更强，同价）
    prompt="""
    用我的真实驾驶数据画一张小红书风格的驾驶人格分享图（生成图片）：
    驾驶人格码：CNSO「深夜静音幽灵」
    一句话画像：很少出动，一动就是深夜悄悄滑过街角。
    自嘲标签：#电机声都嫌吵
    四轴：动力 20/100 · 夜驾占比 18% · 单程平均 9.3km · 出车率 52%
    本月：25 次出行 · 最快 106 km/h
    竖版 3:4 小红书爆款信息图，深色夜景霓虹主题，
    超大 CNSO 视觉锤，数据卡片展示真实数字，四轴条形图可视化。
    """,
    n=1,
    size="1024*1365"                 # 3:4 竖版
)
print(resp.output.results[0].url)
```

将此逻辑封装为 MCP tool，即可让 Claude Code 在 MCP 会话里直接调用生图。
工具定义示例见 [`mcp-server/README.md`](../mcp-server/README.md)（当前未内置，
欢迎 PR——难度：Medium，符合 [`docs/good-first-issues.md`](good-first-issues.md) 定义）。

---

## Pollinations.ai 调用示例

> 适合**图片内容无中文文字**的场景（纯插画、氛围图）；中文海报不推荐。

```bash
# 申请免费 key：https://enter.pollinations.ai
curl -o card.png \
  "https://image.pollinations.ai/prompt/tesla%20night%20drive%20neon%20city%20cyberpunk%20car?model=flux&width=768&height=1024&token=<key>"
```

注意：Pollinations 使用 Flux 底座，不擅长在图片中渲染中文字符。
如果 prompt 中有汉字数字，生成的图片文字通常是乱码或省略的。

---

## Gemini 实测结论

> 以下结论来自 2026-06 本项目实测，供参考。

| 模型 | 结果 | 原因 |
|---|---|---|
| `gemini-2.5-flash-image` | HTTP 429 | **项目免费层按 GCP project 共享，一旦耗尽全模型禁用**（包括 text） |
| `gemini-2.0-flash-preview-image-generation` | HTTP 404 | 模型名不存在（已下线或改名） |
| `imagen-4.0-generate-001` | HTTP 400 | Imagen 系列**永远需要付费**，免费层不支持 |
| `imagen-4.0-fast-generate-001` | HTTP 400 | 同上 |

**免费层用尽后的恢复路径：**新建 Google 账号 + 新 GCP project 重新创 API key；
或在原 project 开通 Cloud Billing 绑卡升级 Paid tier（无免费层限制）。

**如果你有新鲜 Gemini key（新 project）**，在耗尽前质量较好——支持中英文混排，
500 RPD 免费额度足够个人用。请求格式：

```python
import google.generativeai as genai

genai.configure(api_key="<你的 key>")
model = genai.GenerativeModel("gemini-2.5-flash-image")
response = model.generate_content(
    contents=[{"role": "user", "parts": [{"text": "...你的 prompt..."}]}],
    generation_config={"response_modalities": ["IMAGE", "TEXT"]}
)
# response.candidates[0].content.parts 里找 inline_data.data（base64 PNG）
```

---

## creative-prompt 的模型无关性说明

[`plays/driving-personality/creative-prompt.md`](../plays/driving-personality/creative-prompt.md)
里的 prompt 模板是**模型无关的**——同一段 prompt 在 GPT Image、Gemini、
Qwen-Image、Seedream 上均可直接使用，效果差异来自底模能力而非 prompt 本身。

每个 play 的 creative-prompt 文件尾部会注明当前已验证的模型（示例格式）：

```
## 验证记录
- GPT Image (gpt-image-1)  ✅ 双卡实测，质量 ★★★★★
- 豆包生图（Seedream 2.0） ✅ 实测，质量 ★★★★
- Qwen-Image-2.0           ✅ 实测，中文最强
- Gemini flash image       ⚠️ 有条件可用（新 project 免费层）
- Pollinations Flux        ⚠️ 中文场景不推荐
```

如果你用某个模型实测出了好图，欢迎在 PR 里更新对应 play 的 `creative-prompt.md`
的验证记录行——这比 issue 更有价值。

---

## 快速决策树

```
有 ChatGPT Plus / Codex 订阅?
  是 → GPT Image，订阅内免费，质量最高
  否 → 有豆包 / 即梦?
         是 → 豆包生图 / Seedream，免费额度，中文强
         否 → 中国大陆用户 / 想要 MCP 一键生图?
                是 → 火山方舟 Seedream-4.0 (generate_play_image tool)
                     新用户 200 张免费，¥0.2/张，中文最强，无需 VPN
                否 → 想注册 DashScope 国际版?
                       是 → Qwen-Image-2.0，新用户 100 张免费
                       否 → 用内置 SVG 保底卡片（数字 100% 准，无外部依赖）
```

---

## 相关文档

- [creative-prompt 模板（driving-personality）](../plays/driving-personality/creative-prompt.md)
- [Connect ChatGPT Actions](connect-chatgpt.md)
- [Connect Claude MCP](connect-claude-mcp.md)
- [Connect Coze](connect-coze.md)
- [Good first issues（含生图 MCP tool PR 号位）](good-first-issues.md)
