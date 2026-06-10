"""
teslamate-llm-bridge MCP server

Exposes 3 MCP tools that proxy the bridge HTTP API:
  - list_plays           : list all available plays
  - run_play             : run a play for a car, returns JSON result
  - render_play_card     : render a play's share card, returns MCP image content

Configuration (environment variables):
  BRIDGE_URL        bridge base URL  (default: http://localhost:8770)
  BRIDGE_API_TOKEN  Bearer token     (default: empty = no auth)

Transport: stdio (Claude Desktop / Cursor / Codex standard).
Optional streamable-HTTP: set MCP_TRANSPORT=http and MCP_PORT=<port>.
"""

from __future__ import annotations

import base64
import os
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

BRIDGE_URL: str = os.environ.get("BRIDGE_URL", "http://localhost:8770").rstrip("/")
BRIDGE_API_TOKEN: str = os.environ.get("BRIDGE_API_TOKEN", "")

# ---------------------------------------------------------------------------
# HTTP client helper
# ---------------------------------------------------------------------------


def _headers() -> dict[str, str]:
    h: dict[str, str] = {}
    if BRIDGE_API_TOKEN:
        h["Authorization"] = f"Bearer {BRIDGE_API_TOKEN}"
    return h


def _get_json(path: str, params: dict[str, str] | None = None) -> Any:
    """GET bridge endpoint and return parsed JSON. Raises on HTTP error."""
    url = f"{BRIDGE_URL}{path}"
    with httpx.Client(timeout=30) as client:
        resp = client.get(url, headers=_headers(), params=params or {})
        resp.raise_for_status()
        return resp.json()


def _get_bytes(path: str, params: dict[str, str] | None = None) -> bytes:
    """GET bridge endpoint and return raw bytes."""
    url = f"{BRIDGE_URL}{path}"
    with httpx.Client(timeout=60) as client:
        resp = client.get(url, headers=_headers(), params=params or {})
        resp.raise_for_status()
        return resp.content


# ---------------------------------------------------------------------------
# MCP server
# ---------------------------------------------------------------------------

mcp = FastMCP(
    name="teslamate-llm-bridge",
    instructions=(
        "Tools to query your TeslaMate driving data via the teslamate-llm-bridge. "
        "Use list_plays to discover available analyses, run_play to get structured results, "
        "and render_play_card to produce a shareable PNG card."
    ),
)


# ── Tool 1: list_plays ───────────────────────────────────────────────────────


@mcp.tool(
    name="list_plays",
    description=(
        "List all available plays (analyses) loaded by the bridge engine. "
        "Returns each play's name, title, emoji, description, default time window in days, "
        "and whether a share card image is available. "
        "Call this first to discover which play names to pass to run_play."
    ),
)
def list_plays() -> dict[str, Any]:
    """Returns: {data: {plays: [{name, title, emoji, description, scope, default_days, has_card}]}}"""
    return _get_json("/api/v1/plays")


# ── Tool 2: run_play ─────────────────────────────────────────────────────────


@mcp.tool(
    name="run_play",
    description=(
        "Run a play (analysis) for a given TeslaMate car and return a structured JSON result. "
        "If the car has fewer data points than the play's minimum sample threshold, "
        "scored=false is returned with sample count and minimum required. "
        "Use list_plays to see valid play names. "
        "The car_id is the integer primary key from TeslaMate's cars table (usually 1 for a single car)."
    ),
)
def run_play(
    play_name: str,
    car_id: int,
    start_date: str = "",
    end_date: str = "",
) -> dict[str, Any]:
    """
    Args:
        play_name:  kebab-case play name, e.g. "driving-personality"
        car_id:     TeslaMate car ID (integer), e.g. 1
        start_date: ISO 8601 date or datetime for window start (optional, default: end_date - default_days)
        end_date:   ISO 8601 date or datetime for window end   (optional, default: now)

    Returns:
        Scored result with play-specific fields, or unscored with sample/min_sample.
    """
    params: dict[str, str] = {}
    if start_date:
        params["start_date"] = start_date
    if end_date:
        params["end_date"] = end_date
    return _get_json(f"/api/v1/cars/{car_id}/play/{play_name}", params)


# ── Tool 3: render_play_card ─────────────────────────────────────────────────


@mcp.tool(
    name="render_play_card",
    description=(
        "Render a play's share card as a 1080x1080 PNG image and return it as base64. "
        "Returns 404 if the play has no card template (check has_card from list_plays). "
        "If the car has insufficient data, a placeholder card is returned instead of an error."
    ),
)
def render_play_card(
    play_name: str,
    car_id: int,
    start_date: str = "",
    end_date: str = "",
) -> list[ImageContent]:
    """
    Args:
        play_name:  kebab-case play name, e.g. "driving-personality"
        car_id:     TeslaMate car ID (integer)
        start_date: ISO 8601 window start (optional)
        end_date:   ISO 8601 window end   (optional)

    Returns:
        MCP image content item: [{type: "image", data: "<base64>", mimeType: "image/png"}]
    """
    params: dict[str, str] = {}
    if start_date:
        params["start_date"] = start_date
    if end_date:
        params["end_date"] = end_date
    png_bytes = _get_bytes(f"/api/v1/cars/{car_id}/play/{play_name}/card.png", params)
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return [ImageContent(type="image", data=b64, mimeType="image/png")]


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    transport = os.environ.get("MCP_TRANSPORT", "stdio")
    if transport == "http":
        port = int(os.environ.get("MCP_PORT", "8771"))
        mcp.run(transport="streamable-http", host="127.0.0.1", port=port)
    else:
        mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
