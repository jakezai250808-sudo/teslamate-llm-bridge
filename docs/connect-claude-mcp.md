# Connect to Claude via MCP

This guide wires teslamate-llm-bridge to **Claude Desktop** (or any MCP-compatible client such as Cursor or Codex) using the included Python MCP server. Claude can then call three tools — `list_plays`, `run_play`, `render_play_card` — directly from a conversation.

MCP runs locally over `stdio`; no public HTTPS URL is required.

---

## Prerequisites

| What | Where to get it |
|---|---|
| Running bridge on `localhost:8770` | [Quick Start](../README.md#quick-start) |
| Python 3.11+ | `python3 --version` |
| `mcp[cli]>=1.0` + `httpx>=0.27` | `pip install -e mcp-server/` |
| Claude Desktop | [claude.ai/download](https://claude.ai/download) |
| Your `API_TOKEN` (if you set one in `.env`) | check your `.env` |

---

## Step 1 — start the bridge

If it is not already running:

```bash
# Docker Compose (recommended)
docker compose up -d
curl http://localhost:8770/actuator/health   # should return {"status":"UP"}

# or java -jar
cd bridge && java -DTM_DB_HOST=localhost -DTM_DB_PASS=yourpass \
  -jar target/teslamate-llm-bridge-*.jar
```

---

## Step 2 — install the MCP server

```bash
cd mcp-server
pip install -e .     # installs mcp[cli] and httpx into the current Python env
# Verify
python3 server.py --help 2>/dev/null || python3 server.py &
# Ctrl-C after confirming it starts; Claude Desktop manages the process
```

---

## Step 3 — add to Claude Desktop config

Open (create if missing):

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

Add the `teslamate-bridge` entry:

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "python3",
      "args": ["/absolute/path/to/teslamate-llm-bridge/mcp-server/server.py"],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "BRIDGE_API_TOKEN": "your-token-here"
      }
    }
  }
}
```

Replace `/absolute/path/to/teslamate-llm-bridge` with the real path on your machine. If you did not set `API_TOKEN` in `.env`, omit `BRIDGE_API_TOKEN` from `env` (or leave it as an empty string).

Quick way to get the absolute path:

```bash
realpath mcp-server/server.py
```

---

## Step 4 — add via `claude mcp add` (Claude Code CLI alternative)

If you use **Claude Code** (the terminal CLI, not Claude Desktop), use `claude mcp add` instead of editing a JSON file:

```bash
claude mcp add teslamate-bridge \
  -e BRIDGE_URL=http://localhost:8770 \
  -e BRIDGE_API_TOKEN=your-token \
  -- python3 /absolute/path/to/teslamate-llm-bridge/mcp-server/server.py
```

This writes to `~/.claude.json` (Claude Code's local config), **not** to `claude_desktop_config.json`.  Claude Desktop and Claude Code use separate config files — you may need to configure both if you use both clients.

Verify the server connected:

```bash
claude mcp list
# should show: teslamate-bridge: python3 ... - Connected
```

The default scope is `local` (project-specific). To make it available in all projects:

```bash
claude mcp add teslamate-bridge -s user \
  -e BRIDGE_URL=http://localhost:8770 \
  -e BRIDGE_API_TOKEN=your-token \
  -- python3 /absolute/path/to/teslamate-llm-bridge/mcp-server/server.py
```

---

## Step 5 — activate the server

**Claude Desktop**: Quit and reopen. On macOS: `Cmd-Q` then reopen from Applications. After restarting, open a new conversation — you should see **teslamate-bridge** in the tool-sources list (hammer icon). If not, check `~/Library/Logs/Claude/` for MCP startup errors.

**Claude Code**: No restart needed. The server is picked up immediately. Run `claude mcp list` to confirm it shows `Connected`.

---

## Step 6 — first conversation

Try these in order:

```
列出所有可用玩法
```

Claude calls `list_plays` and returns the available play (`driving-personality`).

```
用玩法 driving-personality 分析我的车（car_id=1）
```

Claude calls `run_play` with `play_name=driving-personality, car_id=1` and shows your personality code + persona.

> **Tip**: `car_id` is the integer primary key in TeslaMate's `cars` table — usually `1` for a single car. If you are unsure, check the `CAR_IDS` value in your `.env` or run `docker compose exec bridge curl -s http://localhost:8770/api/v1/cars 2>/dev/null` once a `/cars` listing endpoint is added.

```
给我渲染一张 driving-personality 的战绩卡片
```

Claude calls `render_play_card` and displays the 1080×1080 PNG inline in the chat.

If you get `"scored": false`, the bridge did not find enough data in the default 30-day window. Ask Claude:

```
用 driving-personality 分析我 2024 年全年的数据（car_id=1）
```

Claude will set `start_date=2024-01-01&end_date=2024-12-31` automatically.

---

## Cursor setup

Add to `~/.cursor/mcp.json` (or per-project `.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "python3",
      "args": ["/absolute/path/to/teslamate-llm-bridge/mcp-server/server.py"],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "BRIDGE_API_TOKEN": "your-token-here"
      }
    }
  }
}
```

---

## Codex / OpenAI Agents SDK setup

```python
from agents.mcp import MCPServerStdio

bridge = MCPServerStdio(
    name="teslamate-bridge",
    params={
        "command": "python3",
        "args": ["/absolute/path/to/teslamate-llm-bridge/mcp-server/server.py"],
        "env": {
            "BRIDGE_URL": "http://localhost:8770",
            "BRIDGE_API_TOKEN": "your-token",
        },
    },
)
```

---

## Optional: streamable-HTTP transport (remote access)

If you want the MCP server to listen on a port instead of stdio (for example to share it over a LAN):

```bash
MCP_TRANSPORT=http MCP_PORT=8771 \
BRIDGE_URL=http://your-bridge-host:8770 \
BRIDGE_API_TOKEN=your-token \
python3 mcp-server/server.py
```

Then configure Claude Desktop with:

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "url": "http://localhost:8771/mcp"
    }
  }
}
```

Note: streamable-HTTP transport requires `mcp[cli]>=1.3`.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Tool not visible in Claude Desktop | Restart Claude Desktop; check `~/Library/Logs/Claude/` for MCP startup errors |
| `ModuleNotFoundError: mcp` | Run `pip3 install -e mcp-server/` (or `pip install -e mcp-server/`) in the same Python that `python3` resolves to. On macOS Homebrew Python the command may be `pip3` not `pip`. |
| `Connection refused` on bridge | Confirm `docker compose up -d` succeeded: `curl http://localhost:8770/actuator/health` |
| `401 Unauthorized` | Set `BRIDGE_API_TOKEN` in the config to match your `.env` `API_TOKEN` |
| `render_play_card` returns 404 | That play has no card template (`has_card: false` from `list_plays`) |

---

## Known limitations

- **Claude.ai web** (remote MCP) requires the Remote MCP feature (currently in limited preview). The self-hosted bridge does not yet publish a remote MCP endpoint.
- `render_play_card` returns a base64 PNG. Claude Desktop renders it inline; other clients may show raw base64.
- The bridge must be reachable from the machine running Claude Desktop. HTTPS is not required for localhost.
