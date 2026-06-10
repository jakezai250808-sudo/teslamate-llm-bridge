# 安装测试计划（发布前硬验证）

> 目标：在 staging 真机上验证开源 bridge 的两种安装场景，产出 docs-as-tested 的安装文档
> （命令是跑通的实录，不是想象出来的）。涉及云配置变更，需用户逐项授权。

## 核心拓扑（要验证的常用架构）

```
云端 staging 机                          本地（Jake 的 Mac）
┌──────────────────────────┐            ┌────────────────────┐
│ 官方 TeslaMate (PG :5432) │  ←公网→    │ mcp-server (python) │
│ bridge (:8770)            │  :8770     │   + Agent           │
└──────────────────────────┘            │  (Claude Code/Codex)│
                                         └────────────────────┘
```
TeslaMate + bridge 部署在云端，配置项（MCP server 指向 BRIDGE_URL）在本地 Agent。

## staging 机器实况（2026-06-10 RunCommand 查证）

| 项 | 状态 |
|---|---|
| InstanceId | `i-bp13zkh0xwds0jyolkk9`（staging，**勿碰** staging-gateway/pg/cloudflared 8190 栈） |
| 磁盘 | 40G，剩 11G（够装 TeslaMate 全家桶 + bridge，需顺序启动） |
| 内存 | 3.4G，可用 2.4G（够，但别同时 build 多个） |
| 空闲端口 | 4000(TeslaMate UI)/8770(bridge) 空闲；8190/8443/22/53 占用 |
| 垃圾容器 | `pg-dr-test` / `pg-dr-test2` / `pg-dr-test3`（旧灾备残留，建议清） |

## 需用户授权的 4 项（逐项勾选）

- [ ] **A. 安全组临时开 8770，仅限 Jake 本机公网 IP/32**（测完即删；不开则 MCP 连不上云端 bridge）
- [ ] **B. 清理 3 个 `pg-dr-test*` 残留容器**（省内存，与本次无关的历史垃圾）
- [ ] **C. 往本机 `~/.codex/config.toml` 加一个 mcp server 条目**（测 Codex 接入；测完可留可删）
- [ ] **D. 浏览器驱动 ChatGPT（接口二·路径 B）本轮是否测**（要占用 Jake 的 Chrome；可单独约，先把 API 路径跑完）

## 测试阶段

| # | 阶段 | 内容 | 验收 |
|---|---|---|---|
| 0 | 预检 | Docker Hub 连通性（国内 ECS 拉官方镜像可能慢，备 registry-mirror）；测试目录隔离 `/opt/bridge-e2e/`；绝不碰 staging 8190 栈 | 不影响 staging gate |
| 1 | **场景A·从零** | 照 TeslaMate 官方文档原样装四件套（teslamate+pg+grafana+mosquitto）→ 装 bridge 指向它 → 验「空数据」体验（刚装没数据时玩法提示是否友好）→ 灌 demo 数据模拟老车主 | 两套服务 health UP |
| 2 | 拓扑接线 | 安全组开 8770（仅 Jake IP/32）+ 强随机 API_TOKEN 写 bridge `.env` | 本机 curl 通，其它 IP 拒 |
| 3 | **Agent 矩阵** | **Claude Code**（`claude mcp add`）+ **Codex**（`~/.codex/config.toml`）各走全链：list_plays → run_play → get_creative_prompt → Seedream 出图 | 两个 Agent 都出图成功 |
| 4 | **场景B·补装** | 把阶段1的 TeslaMate 当「已有的」，删 bridge 按补装文档重装一遍 | 纯照文档、无人工补救 |
| 5 | 文档固化 | 跑通的命令回写两份：`install-from-zero.md` / `install-existing-teslamate.md`（docs-as-tested） | 文档=实测记录 |
| 6 | 清理 | 删安全组规则、测试容器、临时目录 | 机器还原 |

## 为什么 Codex 也要测

接口一承诺「多 Agent」。Codex 接入方式与 Claude 不同（`~/.codex/config.toml` TOML vs `claude mcp add`），配置文档要分别写。不测 Codex = 这条承诺没验证。

## 执行方式

staging 操作走 `aliyun ecs RunCommand`（不走 SSH，带 audit）。预计 1–1.5 小时。排在 PR #485 合并后执行。
