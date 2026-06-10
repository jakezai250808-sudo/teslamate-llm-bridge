# teslamate-llm-bridge MCP server

MCP (Model Context Protocol) server that exposes your TeslaMate driving data
as AI tools. Works with Claude Desktop, Cursor, and any MCP-compatible client.

Exposes 4 tools backed by the [teslamate-llm-bridge](https://github.com/teslamate-llm-bridge/teslamate-llm-bridge) HTTP API and 火山方舟 Seedream:

| Tool | Description |
|---|---|
| `list_plays` | List all available plays (analyses) |
| `run_play` | Run a play for a car, get structured JSON result |
| `get_creative_prompt` | Fetch a play's creative-prompt.md template for image generation |
| `generate_play_image` | 调用火山方舟 Seedream-4.0 文生图，返回 base64 图片（中国大陆直连，无需 VPN） |

## Prerequisites

1. A running `teslamate-llm-bridge` instance (default: `http://localhost:8770`)
2. Python 3.10+

## Install

```bash
cd mcp-server
pip install -e .
```

Or with `uv` (recommended for Claude Desktop):

```bash
uv pip install httpx "mcp[cli]"
```

## Claude Desktop configuration

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "python3",
      "args": ["/absolute/path/to/teslamate-llm-bridge/mcp-server/server.py"],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "BRIDGE_API_TOKEN": "your-token-here",
        "ARK_API_KEY": "your-ark-api-key-here",
        "SEEDREAM_MODEL": "doubao-seedream-4-0-250828"
      }
    }
  }
}
```

If you did not set `API_TOKEN` on the bridge, omit `BRIDGE_API_TOKEN`.
`ARK_API_KEY` 和 `SEEDREAM_MODEL` 仅 `generate_play_image` tool 使用；不填时 tool 会返回开通引导。

After saving, restart Claude Desktop. Type "list my available plays" to verify.

### With uv (no global install needed)

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "uv",
      "args": [
        "run", "--with", "mcp[cli]", "--with", "httpx",
        "python3", "/absolute/path/to/mcp-server/server.py"
      ],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "BRIDGE_API_TOKEN": "your-token-here"
      }
    }
  }
}
```

## Cursor configuration

Add to `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "teslamate-bridge": {
      "command": "python3",
      "args": ["/absolute/path/to/mcp-server/server.py"],
      "env": {
        "BRIDGE_URL": "http://localhost:8770",
        "BRIDGE_API_TOKEN": "your-token-here"
      }
    }
  }
}
```

## Codex configuration

Add to `~/.codex/config.toml`:

```toml
[[mcp_servers]]
name = "teslamate-bridge"
command = "python3"
args = ["/absolute/path/to/mcp-server/server.py"]

[mcp_servers.env]
BRIDGE_URL = "http://localhost:8770"
BRIDGE_API_TOKEN = "your-token-here"
```

## Optional: streamable-HTTP transport

For remote access, set `MCP_TRANSPORT=http` and optionally `MCP_PORT=8771`:

```bash
MCP_TRANSPORT=http MCP_PORT=8771 BRIDGE_URL=http://localhost:8770 python3 server.py
```

Note: stdio transport (the default) is recommended for local Claude Desktop use.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `BRIDGE_URL` | `http://localhost:8770` | Base URL of the bridge |
| `BRIDGE_API_TOKEN` | *(empty)* | Bearer token (omit if bridge has no auth) |
| `MCP_TRANSPORT` | `stdio` | `stdio` or `http` |
| `MCP_PORT` | `8771` | Port when `MCP_TRANSPORT=http` |
| `ARK_API_KEY` | *(empty)* | 火山方舟 API Key（`generate_play_image` tool 所需；未设置时 tool 返回配置引导） |
| `SEEDREAM_MODEL` | `doubao-seedream-4-0-250828` | 文生图模型 ID，可切换为方舟支持的其他 Seedream 模型 |
