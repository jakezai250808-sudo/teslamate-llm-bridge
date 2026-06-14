# Contributing to teslamate-llm-bridge

感谢你对本项目的兴趣！欢迎贡献 plays（玩法定义）、引擎改进、文档修缮等。

> **快速入口 / Quick start**
> - 🚀 第一次贡献？加一个 play 最快：[`docs/quickstart-add-a-play.md`](docs/quickstart-add-a-play.md)（10 分钟，纯 YAML，不写 Java/Python）
> - 🐣 新手任务：认领 [`good first issue`](https://github.com/jakezai250808-sudo/teslamate-llm-bridge/labels/good%20first%20issue)
> - 💬 想法 / 提问：[Discussions](https://github.com/jakezai250808-sudo/teslamate-llm-bridge/discussions)
> - 🤖 用 AI agent 贡献：见 [`AGENTS.md`](AGENTS.md)

---

## 贡献者许可协议（CLA）

**为什么需要 CLA？**

本项目采用 AGPL-3.0 开源，同时维护者运营一个基于相同引擎的托管 SaaS 服务（teslaproxy）。
开源版与托管服务共享同一套 play 引擎代码（`play-engine-core` 子模块通过 git subtree 双向同步）。

为了确保维护者有权在 SaaS 托管服务中合法使用所有贡献者的代码（包括在 AGPL 以外的商业许可证下使用），**所有贡献者在第一次提交 PR 时必须签署 CLA**。CLA 内容见 [CLA.md](CLA.md)。

核心要点：
- 你保留对贡献内容的完整版权
- 你授予维护者永久、不可撤销、可再许可（含商业许可）的使用权及专利许可
- 这与 Apache Software Foundation、Google、CNCF 等主流开源基金会的 CLA 实践一致

**如何签署**：CLA Assistant（GitHub Action，无需第三方 OAuth）已启用。第一次提 PR 时，机器人会自动在 PR 上留言提示。你只需在该 PR 里**评论这一行**即可完成签署（一次性，之后所有 PR 自动通过）：

```
I have read the CLA Document and I hereby sign the CLA
```

> 措辞需与上面**完全一致**，机器人才会识别。签署记录提交在 `signatures/version1/cla.json`。

---

## 贡献类型

### 1. Play 玩法定义（`plays/` 目录）

这是社区贡献的主战场。每个 play 是一个声明式 YAML 文件，描述一类 Tesla 数据洞察（行程摘要、充电习惯、驾驶性格分析等）。

> 🚀 **第一次加 play？** 看 [`docs/quickstart-add-a-play.md`](docs/quickstart-add-a-play.md)——10 分钟从零到 PR，不用写 Java/Python。

**快速入门**：
- 参考 `plays/driving-personality/` 或 `plays/monthly-wrapped/` 作为样板
- 玩法 YAML 必须符合 `play.schema.json`（PlayLoader 在加载时校验）
- SQL 语句会经 `PlaySqlGuard` 检查（禁止 INSERT / UPDATE / DELETE / DROP / TRUNCATE / EXECUTE 等写操作）
- 贡献新 play 时在 `plays/` 下新建独立目录，目录名即 play id

### 2. 引擎改进（`play-engine-core/` 目录）

`play-engine-core/` 内的代码通过 git subtree 同步到上游 SaaS 私有仓库，是**单源权威**。改动这里需要更高的审查门槛：
- 需要有充分的理由和测试覆盖
- 不得引入对 SaaS 专属接口（`CarWhitelistProvider`、`PlayAuditLogger`、`PlayScopeChecker`）的直接依赖
- 提 PR 前请先 issue 讨论设计

### 3. 文档与 MCP 工具

欢迎改进 `docs/`、`README.md`、`DEMO.md` 以及 `mcp-server/` 下的工具定义。

---

## 开发流程

```bash
# 构建（需要 JDK 21 + Docker Desktop）
cd bridge
mvn compile

# 运行测试
mvn test

# 本地启动（含 TeslaMate 依赖）
docker compose up -d
```

---

## PR 规范

- 标题使用 conventional commits 格式：`feat(plays):` / `fix(engine):` / `docs:` 等
- 新 play 提交附上示例输出截图（如有）
- 保持 PR 单一职责，不混搭引擎改动和 play 内容改动

---

## 行为准则

请遵守基本的开源社区礼仪。本项目暂未采用正式 Code of Conduct，但维护者保留移除不当内容或屏蔽恶意贡献者的权利。
