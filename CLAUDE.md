# teslamate-llm-bridge — Claude Code 工作指南

把 TeslaMate 的原始 PostgreSQL 数据变成 LLM 可以直接聊的内容：声明式玩法（play）引擎 + AI 生图分享卡，一套定义同时支持 ChatGPT Actions / Coze 插件 / MCP 三个平台。

---

## 模块边界

| 目录 | 是什么 | 主要技术 |
|---|---|---|
| `bridge/` | Spring Boot 3.3 引擎，groupId `io.teslabridge` artifactId `teslamate-llm-bridge`，端口 8770 | Java 21 + Spring Boot 3.3 + JDBC |
| `bridge/src/main/java/io/teslabridge/play/` | **玩法引擎核心**：manifest loader、SQL 静态守卫、compute 管道 | — |
| `plays/` | 声明式玩法集合，每个子目录一个玩法（`play.yaml` + 可选 `creative-prompt.md` + `fixtures.yaml`） | YAML |
| `plays/play.schema.json` | 玩法 manifest JSON Schema（draft 2020-12），**禁止改动** | — |
| `mcp-server/` | Python MCP server，proxy HTTP API，4 个工具（`list_plays` / `run_play` / `get_creative_prompt` / `generate_play_image`） | Python 3.11+ + FastMCP + httpx |
| `tools/` | CI 工具：`validate_plays.py`（引擎镜像验证 + fixture runner） | Python |
| `bin/` | 本地辅助脚本：`play-load-data.sh`、`play-preview.sh`、`play-compat-test.sh` | bash |
| `docs/` | 规范、平台接入指南、good-first-issues | markdown |
| `ops/` | Docker Compose 编排 + TeslaMate 官方 schema 参考 | docker compose / SQL |

---

## 常用命令

### Bridge（Spring Boot 引擎）

```bash
cd bridge
mvn -DskipTests package              # 打 jar (target/teslamate-llm-bridge-*.jar)
mvn test                             # 全套测试（需要本地 PostgreSQL 或 Testcontainers）
mvn -Dtest=ClassName test            # 单测
mvn spotless:apply                   # 格式化
mvn compile                          # 仅编译（提交前预检）
```

### 本地运行（不用 Docker）

```bash
# 前提：设好 TM_DB_* 环境变量
cd bridge
mvn -DskipTests package
java -DTM_DB_HOST=localhost -DTM_DB_PASS=yourpass \
     -jar target/teslamate-llm-bridge-*.jar
# 健康检查
curl http://localhost:8770/actuator/health
```

### Docker Compose 一键启动

```bash
cp .env.example .env        # 填写 TM_DB_HOST / TM_DB_PASS / API_TOKEN
docker compose up -d
curl http://localhost:8770/api/v1/plays   # 验证
```

### 玩法验证（等同 CI）

```bash
pip install pyyaml                         # 仅第一次
python3 tools/validate_plays.py            # 引擎镜像验证 + 所有 fixture 跑通

# schema 验证
yq -o=json '.' plays/<name>/play.yaml > /tmp/play.json
npx --yes ajv-cli@5 validate --spec=draft2020 \
  -s plays/play.schema.json -d /tmp/play.json
```

### 本地预览玩法结果

```bash
bin/play-preview.sh <play-name> <car_id>
# 例：
bin/play-preview.sh driving-personality 1
```

### MCP server 启动

```bash
cd mcp-server
pip install -e .                            # 安装依赖（首次）
BRIDGE_URL=http://localhost:8770 \
BRIDGE_API_TOKEN=your-token \
python3 server.py                           # stdio 模式（Claude Desktop 用）
```

接入 Claude Desktop 详见 `docs/connect-claude-mcp.md`。

### 兼容性 diff

```bash
bin/play-compat-test.sh                     # 比对本地 bridge 与参考响应
```

---

## 加一个玩法

请完整阅读并遵循 **`AGENTS.md`**（为 AI agent 写的逐步指南）。快速入口：

```
新建 plays/<name>/play.yaml + fixtures.yaml
→ python3 tools/validate_plays.py
→ 提 PR（标题格式: play: <name> — <一句话描述>）
```

**不需要改任何 Java 或 Python。**

---

## 不要碰的东西

| 对象 | 原因 |
|---|---|
| `LICENSE` | AGPL-3.0，禁止修改 |
| `plays/play.schema.json` | 与引擎强绑定，schema 变更需要同步引擎 + 所有现有玩法 |
| `bridge/src/main/java/io/teslabridge/play/` 核心引擎 | 已有 E2E 测试覆盖；改之前必须有充分测试证明；不因单个玩法需求而扩展（开 issue 讨论） |
| `.env` / `.env.local` | 含数据库凭据，禁止 Claude 直接 Edit/Write |

---

## 与 AGENTS.md 的分工

| 文件 | 覆盖范围 |
|---|---|
| **CLAUDE.md（本文件）** | 项目全局：模块边界、构建/运行/测试命令、禁止改动清单 |
| **AGENTS.md** | **加玩法专项**：9 步详细操作 + 约束清单 + 验证命令 + PR checklist，为 AI agent 量身写的 |

---

## 测试约定

- `bridge/` 测试用 JUnit 5 + Testcontainers（需要 Docker Desktop）
- `tools/validate_plays.py` 是引擎镜像，在 Python 端重现 SQL 守卫 + compute 管道，**不**连真实数据库
- 提交前最小预检：`(cd bridge && mvn compile spotless:apply) && python3 tools/validate_plays.py`
