# 在 Codex CLI 里使用 teslamate-llm-bridge MCP

本文面向「想用 OpenAI Codex CLI 接 teslamate-llm-bridge」的开源用户。

---

## 1. 前置准备

- Codex CLI 已安装（`codex --version` 能返回版本号）
- teslamate-llm-bridge 本地服务已运行（默认端口 8770）
- MCP server 依赖已安装：

  ```bash
  cd <repo>/mcp-server
  pip install -e .           # 或：pip install --break-system-packages httpx mcp
  ```

- 火山方舟 API Key（仅需生图时）：注册 https://console.volcengine.com/ark，
  开通 Doubao-Seedream-4.0，在「API Key 管理」新建 key。

---

## 2. 配置 config.toml

Codex CLI 的配置文件通常位于 `~/.codex/config.toml`。

在文件末尾追加以下内容（**将占位符替换为真实值，禁止把真实 key 提交到任何 repo/文档**）：

```toml
# ── teslamate-llm-bridge MCP（HTTP transport 模式，绕开 seatbelt 封网）──────
[mcp_servers.tmbridge]
type = "http"
url  = "http://localhost:8771/mcp"
```

同时确认以下全局开关已设置（或加到你的 `config.toml`）：

```toml
sandbox_mode    = "danger-full-access"
approval_policy = "never"
```

> **为什么是 `type = "http"` 而不是 `type = "stdio"`？**
>
> 见下方「排障」章节。简而言之：macOS seatbelt 对 Codex 启动的子进程封堵出站网络，
> stdio 模式的 MCP server 无法访问火山方舟 API（`generate_play_image` 调用超时）。
> 将 MCP server 预先在 sandbox **外**以 HTTP 模式独立启动，Codex 通过 `type="http"` 接入，
> 完全绕开此限制。

---

## 3. 每次启动前：在 sandbox 外运行 MCP server

每次开机（或服务重启）后，在终端里手动启动一次 MCP server（HTTP transport 模式）：

```bash
MCP_TRANSPORT=http \
MCP_PORT=8771 \
BRIDGE_URL=http://localhost:8770 \
BRIDGE_API_TOKEN="${BRIDGE_API_TOKEN}" \
ARK_API_KEY="${ARK_API_KEY}" \
python <repo>/mcp-server/server.py \
  >> /tmp/bridge-mcp-http.log 2>&1 &

echo "MCP server PID: $!"
```

- 将 `${BRIDGE_API_TOKEN}` 和 `${ARK_API_KEY}` 替换为真实值，**或**在 shell profile 里
  提前 `export` 这两个变量，再执行上面的命令——这样 key 不会出现在命令行历史里。
- 验证已启动：`curl -s http://localhost:8771/mcp` 应返回 JSON（不报 connection refused）。

---

## 4. 调用 Codex

```bash
CODEX_SANDBOX_NETWORK_DISABLED=0 \
  codex exec \
  --sandbox danger-full-access \
  --dangerously-bypass-approvals-and-sandbox \
  --skip-git-repo-check \
  "调用 run_play，play_name=driving-personality，car_id=99，把 code 字段告诉我"
```

---

## 5. 验证命令

### 测试 1：run_play（不需要火山 key）

```bash
CODEX_SANDBOX_NETWORK_DISABLED=0 \
  codex exec \
  --sandbox danger-full-access \
  --dangerously-bypass-approvals-and-sandbox \
  --skip-git-repo-check \
  "用 MCP tmbridge 的 run_play 工具，play_name=driving-personality，car_id=99，把 code 字段的值告诉我"
```

**期望结果**：返回 `code: "FNLE"`（或其他 4 字母人格码；demo car_id=99 数据不足时返回 `scored: false`，两者均为正常）。

### 测试 2：generate_play_image（需要 ARK_API_KEY）

```bash
CODEX_SANDBOX_NETWORK_DISABLED=0 \
  codex exec \
  --sandbox danger-full-access \
  --dangerously-bypass-approvals-and-sandbox \
  --skip-git-repo-check \
  "用 MCP tmbridge 按顺序：list_plays → run_play(driving-personality, 99) → get_creative_prompt → 填充占位符 → generate_play_image(size=1080x1920)，返回生成图片"
```

**期望结果**：Codex 返回 base64 图片内容或将图片保存到磁盘。

---

## 6. 排障（重点）

### 现象：`user cancelled MCP tool call`

**根因**：`codex exec`（非交互模式）stdin 被关闭，MCP tool call 走到审批流时
立即返回 `user cancelled`，与 `approval_policy = "never"` 等配置无关
（[issue #24135](https://github.com/openai/codex/issues/24135)，截至本文写作时仍未修复）。

**唯一 workaround**：

```bash
codex exec --dangerously-bypass-approvals-and-sandbox ...
```

加上这个 flag 后，审批流被完全跳过，MCP tool call 正常执行。

---

### 现象：`generate_play_image` 返回 `read operation timed out`（stdio 模式下）

**根因（双重封网）**：

1. macOS seatbelt 在 OS 层对 Codex 启动的子进程静默设置
   `CODEX_SANDBOX_NETWORK_DISABLED=1`，封堵出站网络。
   这个封堵对 Codex 启动的 **MCP server 子进程**同样生效——
   即使你在 `config.toml` 里写了 `[sandbox_workspace_write] network_access = true`，
   seatbelt 层也不认这条配置（[issue #10390](https://github.com/openai/codex/issues/10390)）。

2. stdio 模式的 MCP server 是 Codex 的子进程，继承了 seatbelt 封网，
   因此 `generate_play_image` 调用火山方舟 API（~10 秒的真实延迟）会在沙箱内超时。

**解法（已验证可行）**：

将 MCP server 在 Codex sandbox **外**预先以 HTTP transport 模式独立启动（见第 3 节），
Codex 通过 `type = "http"` 接入，绕开子进程 seatbelt 封网。

配置对比：

| 模式 | config.toml | run_play | generate_play_image |
|---|---|---|---|
| `type = "stdio"` + bypass | 不需要额外启动 | PASS | FAIL（seatbelt 封网超时）|
| `type = "http"` + 预启动 + bypass | 需要手动启动 HTTP server | PASS | **PASS** |

---

### 现象：`connection refused` on `http://localhost:8771/mcp`

MCP server 没有启动，或启动后很快退出。

排查步骤：

```bash
# 查进程是否存活
pgrep -fl "server.py"

# 看日志
tail -30 /tmp/bridge-mcp-http.log

# 手动前台跑，看报错
MCP_TRANSPORT=http MCP_PORT=8771 \
  BRIDGE_URL=http://localhost:8770 \
  python <repo>/mcp-server/server.py
```

常见原因：依赖未安装（`pip install httpx mcp`）；8771 端口被占（换一个端口，同步改 config.toml 里的 url）。

---

### 现象：调用成功但未见图片（generate_play_image 返回引导文本）

`ARK_API_KEY` 未传入 MCP server 进程。检查启动命令里是否有 `ARK_API_KEY=<你的key>`，
或者确认 shell 里 `echo $ARK_API_KEY` 有值再执行启动命令。

---

## 7. 迭代验证记录（供参考）

| 轮次 | 配置 | run_play 结果 | generate_play_image 结果 |
|---|---|---|---|
| 1 | `type=stdio` + `--dangerously-bypass-approvals-and-sandbox` | PASS，code=FNLE | FAIL — `read operation timed out`（seatbelt 封网）|
| 2 | `type=http`，同样 bypass flags | PASS，code=FNLE | **PASS** — 返回 ImageContent，生图成功 |

根因分析见第 6 节。
