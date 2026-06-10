# 玩法引擎架构 — 两大接口模型

> 版本：v1（2026-06-10）  
> 范围：开源版（teslamate-llm-bridge），不含 SaaS 托管版内部实现细节。  
> 托管版说明：本项目有一个基于相同引擎的托管版实现，支持多租户存储；本文只描述开源版架构。

---

## 概览

整个玩法引擎由**两大接口**构成：

```
存储（TeslaMate）
    ↓
【接口一：数据读取】  ← 玩法 = 读什么 + 怎么算
    ↓
结构化结论 + 生图描述模板
    ↓
【接口二：生图】  ← 渠道 × 接入方式 交叉矩阵
    ↓
分享图（小红书 / 朋友圈格式）
```

消费者是任意 Agent 平台：Claude Code（MCP 协议）、ChatGPT Custom GPT（HTTP/OpenAPI）、扣子智能体（HTTP/OpenAPI），三者对等，无能力鸿沟。

---

## 接口一：数据读取

### 1.1 存储实现

开源版使用**官方 TeslaMate**（单租）作为唯一存储实现。用户自部署 TeslaMate，引擎直连数据库，只读聚合，不写入任何数据。

另有托管版实现多租户存储，详见 `docs/connect-*.md` 中的托管版接入说明。

### 1.2 玩法

**玩法**是接口一的核心抽象单元，每个玩法自我完备，包含三部分：

1. **读什么数据**：一条只读聚合 SQL，绑定 `:car_id`（防止跨车读取）、时间窗口参数；
2. **怎么算（算的配方）**：声明式计算流水线——算术表达式、分档映射、字符串插值、键值表查找，四类步骤有序组合；
3. **产出**：结构化结论（JSON）+ 生图描述模板（供接口二消费）。

玩法以**声明式文件夹**的形式存在，无需修改任何引擎代码即可新增：

```
plays/<名称>/
├── play.yaml          # 玩法声明：SQL + 计算配方 + 产出契约
├── creative-prompt.md # 生图描述模板（供接口二各渠道填充）
└── fixtures.yaml      # CI 测试用例（必须）
```

> **注意**：SVG 保底卡片（`card.svg.tmpl`）不在开源版中提供。SVG 渲染效果较差，已从开源版路线中撤出；如需数字精确的保底卡片，可使用结构化 JSON 数据自行排版，或使用托管版。

想新增玩法，参考 [`AGENTS.md`](../AGENTS.md) 的 9 步指南；规范细节见 [`docs/play-manifest-spec.md`](play-manifest-spec.md)。

### 1.3 样本门禁

玩法声明中可设最小样本门禁（`min_sample`）：行程数不足时直接返回"数据不足"，不运行计算流水线，避免统计无意义的结论。

### 1.4 对外多协议

接口一对外暴露两套等价协议：

| 协议 | 消费者 | 工具 / 端点形式 |
|---|---|---|
| **MCP**（Model Context Protocol） | Claude Code、Codex、任意 MCP 客户端 | `list_plays` / `run_play` / `get_creative_prompt` / `generate_play_image` |
| **HTTP / OpenAPI** | ChatGPT Custom GPT、扣子智能体、任意 HTTP 客户端 | REST endpoint，遵循项目 OpenAPI spec |

两套协议能力集严格 1:1 映射，无"某平台独有玩法"的情况。

接入各平台的具体步骤：

- Claude MCP：[`docs/connect-claude-mcp.md`](connect-claude-mcp.md)
- ChatGPT：[`docs/connect-chatgpt.md`](connect-chatgpt.md)
- 扣子：[`docs/connect-coze.md`](connect-coze.md)

### 1.5 demo 数据模式

接口一支持 demo 数据（合成 TeslaMate 库）：无需真实车辆即可体验完整玩法流程，适合本地演示与贡献者开发调试。demo 数据与出图方式**正交**——使用哪套数据不影响接口二如何生图。

---

## 接口二：生图

接口二负责把「填好数据的生图描述模板」变成一张可分享的图片。实现是一个**渠道 × 接入方式**的交叉矩阵，任何一个格子都是合法路径。

### 2.1 渠道维度

| 渠道 | 中文文字能力 | 可用性 | 备注 |
|---|---|---|---|
| **GPT Image**（gpt-image-1 / DALL-E 3） | 极强，中文字符准确 | ChatGPT Plus / Codex 订阅内 | 当前质量最高路径 |
| **Seedream**（火山方舟 Seedream-4.0） | 极强，中文海报原生支持 | 新用户 200 张免费，后 ¥0.2/张 | 中国大陆直连，MCP tool 内置 |
| **豆包 / 即梦**（Seedream 底座） | 强，小字布局稳定 | 平台免费额度内 | 扣子 bot 内可直接触发 |
| **Qwen-Image-2.0**（DashScope 国际版） | 极强，商用 API 中文最稳 | 新用户 100 张免费 | 需注册 DashScope 国际版 |
| Gemini flash image | 较好，中英混排 | 需 GCP billing（无真正免费层） | 有条件可用 |
| Pollinations Flux | 差，中文文字易乱 | 理论无限 | 不推荐中文海报场景 |

详细的渠道评测、调用示例和配置说明，见 [`docs/image-generation.md`](image-generation.md)。

### 2.2 接入方式维度

| 接入方式 | 说明 | 谁来实现 |
|---|---|---|
| **API 直调** | 代码直接调生图 API（如 Seedream / OpenAI Image API） | MCP server 内置 `generate_play_image` tool |
| **浏览器驱动** | Agent 用自己的 Chrome MCP 操作用户已登录的 ChatGPT / 豆包 / 即梦网页 | Agent 自己持有并操作浏览器；本项目只提供使用文档，不封装代码 |
| **Agent 平台自带** | 用户就在 ChatGPT / 扣子对话里，平台本身有生图能力，直接触发 | 无需额外接入，由 Agent 平台处理 |

> **设计边界**：本项目不封装「浏览器驱动」路径的代码实现，只提供丰富文档让 Agent 自己装上这条路。API 直调路径由 MCP server 内置 tool 覆盖。

### 2.3 无生图渠道的用户

没有任何生图渠道的用户 = 不玩图片分享，可接受。接口一的结构化结论（JSON 数字、人格码、文字描述）本身即有价值，图片是增强层而非必须项。

---

## 铁律汇总

| 规则 | 说明 |
|---|---|
| 开源版无 SVG 保底卡片 | SVG 渲染效果差已从开源路线撤出；数字精确的保底卡片在托管版中提供 |
| demo 数据与出图正交 | 使用合成数据不影响接口二走哪条生图路径 |
| 无生图渠道可接受 | 图片是增强层，JSON 结构化结论是核心产出 |
| play SQL 只查公共表 | 不得查任何非 TeslaMate 公共表字段，保持官方/托管版可互换 |
| 两协议能力 1:1 | MCP tool 集 == OpenAPI 端点集，无平台独有能力 |

---

## 代码对照表

> 本节是唯一出现具体模块名、目录名的位置，供开发者从架构概念找到代码入口。

| 架构概念 | 代码位置 / 模块 |
|---|---|
| 接口一（数据读取）运行时 | `bridge/`（Spring Boot 模块，play-engine-core 子树） |
| 玩法声明文件夹 | `plays/<name>/play.yaml` + `fixtures.yaml` + `creative-prompt.md` |
| 玩法 JSON Schema | `plays/play.schema.json` |
| play-compat CI | `.github/workflows/validate-plays.yml` |
| MCP server（接口一对外） | `mcp-server/server.py`（4 tools：list_plays / run_play / get_creative_prompt / generate_play_image） |
| HTTP / OpenAPI（接口一对外） | `bridge/` Spring Boot REST endpoint（2 端点：listPlays / runPlay） |
| 接口二 API 直调（Seedream） | `mcp-server/server.py` 中的 `generate_play_image` tool |
| 生图描述模板 | `plays/<name>/creative-prompt.md` |
| demo 数据 | `ops/demo/` seed SQL（compose 里的 demo profile） |
| 贡献指南（新增玩法） | `AGENTS.md` + `docs/play-manifest-spec.md` |
