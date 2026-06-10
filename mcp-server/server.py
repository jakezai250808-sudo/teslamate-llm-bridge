"""
teslamate-llm-bridge MCP server

Exposes 4 MCP tools:
  - list_plays           : list all available plays
  - run_play             : run a play for a car, returns JSON result
  - render_play_card     : render a play's share card, returns MCP image content
  - generate_play_image  : call 火山方舟 Seedream 文生图 API, return generated image

Configuration (environment variables):
  BRIDGE_URL        bridge base URL  (default: http://localhost:8770)
  BRIDGE_API_TOKEN  Bearer token     (default: empty = no auth)

  ARK_API_KEY       火山方舟 API Key（从方舟控制台「API Key 管理」获取）
  SEEDREAM_MODEL    文生图模型 ID    (default: doubao-seedream-4-0-250828)

Transport: stdio (Claude Desktop / Cursor / Codex standard).
Optional streamable-HTTP: set MCP_TRANSPORT=http and MCP_PORT=<port>.
"""

from __future__ import annotations

import base64
import json
import os
import re
import urllib.error
import urllib.request
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

BRIDGE_URL: str = os.environ.get("BRIDGE_URL", "http://localhost:8770").rstrip("/")
BRIDGE_API_TOKEN: str = os.environ.get("BRIDGE_API_TOKEN", "")

# 火山方舟 Seedream 文生图配置
# ARK_API_KEY 未设置时，generate_play_image tool 仍注册，调用时返回友好引导文本
ARK_API_KEY: str = os.environ.get("ARK_API_KEY", "")
SEEDREAM_MODEL: str = os.environ.get("SEEDREAM_MODEL", "doubao-seedream-4-0-250828")
ARK_IMAGES_URL: str = "https://ark.cn-beijing.volces.com/api/v3/images/generations"

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
        "render_play_card to produce a shareable PNG card, "
        "and generate_play_image to create a social-share image via 火山方舟 Seedream (China-direct)."
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


# ── Tool 4: generate_play_image ─────────────────────────────────────────────


@mcp.tool(
    name="generate_play_image",
    description=(
        "用火山方舟 Seedream-4.0 文生图模型生成一张分享图，并以 base64 图片形式返回。"
        "调用方需先用 run_play 拿到 JSON 结果，再用 plays/<name>/creative-prompt.md 里的模板"
        "填入真实数据，得到完整 prompt 后传入此工具。"
        "需要设置环境变量 ARK_API_KEY（火山方舟 API Key）；未设置时返回配置引导。"
        "中国大陆直连，无需 VPN，原生中文海报能力强，新用户 200 张免费。"
    ),
)
def generate_play_image(
    prompt: str,
    size: str = "1024x1024",
) -> list[ImageContent] | str:
    """
    Args:
        prompt: 已用 creative-prompt 模板填充完毕的完整文生图 prompt（调用方负责填充占位符）
        size:   输出尺寸，方舟支持 "1024x1024"（默认）/ "1080x1920" / "1920x1080" 等

    Returns:
        成功：MCP image content 列表 [{type: "image", data: "<base64>", mimeType: "image/png"}]
        未配置 ARK_API_KEY：说明文本（str），告知如何开通并配置
        API 报错：可读中文错误文本（str）
    """
    # ── 校验 size 格式（避免无谓网络往返，给出支持列表） ────────────────────
    if not re.fullmatch(r"\d+x\d+", size):
        return (
            f"❌ 尺寸参数格式不正确：'{size}'。\n"
            "请使用 `宽x高` 格式，例如：`1024x1024`（默认）、`1080x1920`（竖版）、`1920x1080`（横版）。"
        )

    # ── 未配置 Key：返回友好引导，不抛裸异常 ────────────────────────────────
    if not ARK_API_KEY:
        return (
            "⚠️ 未配置火山方舟 API Key，无法调用 Seedream 文生图。\n\n"
            "**三步开通（约 5 分钟）：**\n"
            "1. 注册火山方舟：https://console.volcengine.com/ark\n"
            "2. 在「模型广场」搜索「Doubao-Seedream-4.0」→「开通管理」→「立即开通」"
            "（新用户赠 200 张免费额度，之后 ¥0.2/张）\n"
            "3. 「API Key 管理」→「新建 API Key」→ 复制 key\n\n"
            "**配置方式（在 MCP server 的 env 块里加）：**\n"
            "```\n"
            'ARK_API_KEY=<你的 key>\n'
            "SEEDREAM_MODEL=doubao-seedream-4-0-250828   # 可选，默认即此值\n"
            "```\n\n"
            "配置后重启 Claude Desktop 即可使用。"
        )

    # ── 构造方舟 images/generations 请求 ────────────────────────────────────
    payload = json.dumps(
        {
            "model": SEEDREAM_MODEL,
            "prompt": prompt,
            "size": size,
            "response_format": "b64_json",  # 直接取 base64，省去第二次 HTTP 下载并消除 SSRF 风险
        }
    ).encode("utf-8")

    req = urllib.request.Request(
        ARK_IMAGES_URL,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            # 安全红线：key 只从 env 读，仅出现在 HTTP header，绝不写日志/文件
            "Authorization": f"Bearer {ARK_API_KEY}",
        },
    )

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # 把方舟的 error.code / error.message 透传为可读中文错误
        try:
            raw = exc.read()
            err_body = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            err_body = {}
        err_obj = err_body.get("error", {})
        # code 可能是字符串（方舟业务码）或整数（HTTP 状态码 fallback），统一转 str 再比较
        code = str(err_obj.get("code", exc.code))
        message = err_obj.get("message", exc.reason or "未知错误")

        # 常见错误码给出操作指引
        hint = ""
        if code == "ModelNotOpen":
            hint = "\n\n💡 提示：请前往方舟控制台「开通管理」→ 搜索 Seedream-4.0 → 开通模型后重试。"
        elif code in ("InvalidApiKey", "AuthenticationFailed"):
            hint = "\n\n💡 提示：请检查 ARK_API_KEY 是否正确，并确认 Key 未过期。"
        elif code == "RateLimitExceeded":
            hint = "\n\n💡 提示：请求频率超限，稍后重试，或在方舟控制台提升配额。"
        elif code == "InsufficientBalance":
            hint = "\n\n💡 提示：免费额度已用完，请在方舟控制台充值后继续使用（¥0.2/张）。"

        return f"❌ 方舟 API 返回错误 [{code}]：{message}{hint}"

    except urllib.error.URLError as exc:
        return f"❌ 网络请求失败：{exc.reason}。请检查网络连通性（需能访问 ark.cn-beijing.volces.com）。"

    except Exception as exc:  # noqa: BLE001
        return f"❌ 生图请求异常：{exc}。请稍后重试，若持续失败请检查网络或联系方舟支持。"

    # ── 从响应体取 b64_json 数据 ─────────────────────────────────────────────
    try:
        item = body["data"][0]
        b64: str = item["b64_json"]
    except (KeyError, IndexError, TypeError) as exc:
        return f"❌ 方舟响应格式异常，无法取得图片数据（{exc}）。响应片段：{str(body)[:300]}"

    # 优先用响应体里的 content-type 字段（部分规格返回 JPEG），无则 fallback png
    mime: str = item.get("content_type", "image/png").split(";")[0].strip() or "image/png"
    return [ImageContent(type="image", data=b64, mimeType=mime)]


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
