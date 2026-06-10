#!/usr/bin/env bash
# ================================================================
# demo.sh — teslamate-llm-bridge end-to-end demo
#
# Walks through the complete flow:
#   1. Start the bridge (docker compose up)
#   2. Wait for health
#   3. List plays
#   4. Run driving-personality and display the JSON result
#   5. Show next-step guidance for image generation (Interface 2)
#
# Usage:
#   bash bin/demo.sh [--car-id N] [--demo]
#
#   --car-id N   TeslaMate car_id to use (default: 1)
#   --demo       Use built-in demo data (no TeslaMate required).
#                Starts the 'demo' compose profile with synthetic
#                45-day Model Y LR Shanghai data, sets car_id=99.
#
# Pre-requisites (non-demo mode):
#   - .env file exists with TM_DB_* set (copy from .env.example)
#   - Docker Desktop running
# ================================================================

set -euo pipefail

CAR_ID=1
DEMO_MODE=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --car-id) CAR_ID="$2"; shift 2;;
    --demo)   DEMO_MODE=true; shift;;
    *) echo "Unknown arg: $1" >&2; exit 1;;
  esac
done

# --demo auto-sets car_id=99 unless explicitly overridden
if [[ "$DEMO_MODE" == "true" && "$CAR_ID" == "1" ]]; then
  CAR_ID=99
fi

BASE_URL="http://localhost:8770"
AUTH_HEADER=""

# Pick up API_TOKEN from .env if present (only in non-demo mode)
if [[ "$DEMO_MODE" == "false" && -f .env ]]; then
  TOKEN=$(grep -E '^API_TOKEN=' .env | cut -d= -f2- | tr -d '"' || true)
  if [[ -n "$TOKEN" ]]; then
    AUTH_HEADER="-H \"Authorization: Bearer $TOKEN\""
    echo ">>> API_TOKEN detected — will include Bearer token in requests"
  fi
fi

# Helper: curl with optional auth
_curl() {
  if [[ -n "$AUTH_HEADER" ]]; then
    curl -sf -H "Authorization: Bearer $TOKEN" "$@"
  else
    curl -sf "$@"
  fi
}

echo ""
echo "================================================================"
echo " teslamate-llm-bridge demo"
if [[ "$DEMO_MODE" == "true" ]]; then
  echo " Mode: DEMO (synthetic data, car_id=99, no TeslaMate required)"
fi
echo "================================================================"
echo ""

# ------------------------------------------------------------------
if [[ "$DEMO_MODE" == "true" ]]; then
  echo ">>> [1/4] Starting demo profile (postgres-demo + bridge-demo)..."
  echo "    First run pulls postgres:16-alpine and seeds ~45 days of data."
  echo "    This takes ~30s on first boot."
  docker compose --profile demo up -d
  SERVICE_NAME="bridge-demo"
else
  echo ">>> [1/4] Starting bridge (docker compose up -d)..."
  docker compose up -d
  SERVICE_NAME="bridge"
fi

echo "    Waiting up to 90s for health..."
for i in $(seq 1 45); do
  if _curl "${BASE_URL}/actuator/health" -o /dev/null 2>/dev/null; then
    echo "    Health: UP (after ~$((i*2))s)"
    break
  fi
  if [[ $i -eq 45 ]]; then
    echo "    ERROR: Bridge did not become healthy within 90s"
    docker compose logs "${SERVICE_NAME}" 2>/dev/null | tail -30 || true
    exit 1
  fi
  sleep 2
done

# ------------------------------------------------------------------
echo ""
echo ">>> [2/4] Listing available plays..."
PLAYS_JSON=$(_curl "${BASE_URL}/api/v1/plays")
echo "$PLAYS_JSON" | python3 -m json.tool 2>/dev/null || echo "$PLAYS_JSON"

# ------------------------------------------------------------------
echo ""
echo ">>> [3/4] Running driving-personality (car_id=${CAR_ID})..."
if [[ "$DEMO_MODE" == "true" ]]; then
  # Demo data spans 2026-04-27 ~ 2026-06-10; use a fixed window that covers it
  START_DATE="2026-05-11"
  END_DATE="2026-06-11"
  RESULT=$(_curl "${BASE_URL}/api/v1/cars/${CAR_ID}/play/driving-personality?start_date=${START_DATE}&end_date=${END_DATE}")
else
  START_DATE=$(date -v-90d '+%Y-%m-%d' 2>/dev/null || date -d '90 days ago' '+%Y-%m-%d' 2>/dev/null || echo "2024-01-01")
  RESULT=$(_curl "${BASE_URL}/api/v1/cars/${CAR_ID}/play/driving-personality?start_date=${START_DATE}")
fi
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

# Extract persona name for display
PERSONA=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); p=d.get('data',{}).get('persona',{}); print(p.get('name','(no persona — try a wider window)'))" 2>/dev/null || echo "(could not parse persona)")
echo ""
echo "    Persona: ${PERSONA}"

# ------------------------------------------------------------------
echo ""
echo ">>> [4/4] Done!"
echo ""
echo "================================================================"
echo " Next steps — Interface 2 (image generation):"
echo ""
echo " Path A — API direct (Seedream):"
echo "   export ARK_API_KEY=<your-key>"
echo "   Use 'get_creative_prompt driving-personality' in Claude MCP"
echo "   then 'generate_play_image' with the filled prompt"
echo ""
echo " Path B — Browser-driven ChatGPT / Doubao:"
echo "   See AGENTS.md §路径 B for step-by-step instructions"
echo ""
echo " Path C — ChatGPT/Doubao native (OpenAPI users):"
echo "   Ask your bot: '帮我生成驾驶人格分享图' — DALL-E 3 / Seedream built-in"
echo ""
echo " Other:"
echo "   - Connect to Claude Desktop (MCP):  docs/connect-claude-mcp.md"
echo "   - Connect to ChatGPT Actions:        docs/connect-chatgpt.md"
echo "   - Connect to Coze:                   docs/connect-coze.md"
echo "   - Add a new play:                    AGENTS.md"
if [[ "$DEMO_MODE" == "true" ]]; then
  echo ""
  echo " Stop demo:"
  echo "   docker compose --profile demo down"
fi
echo ""
echo " To record an asciinema screencast of this demo:"
if [[ "$DEMO_MODE" == "true" ]]; then
  echo "   asciinema rec demo.cast --command 'bash bin/demo.sh --demo'"
else
  echo "   asciinema rec demo.cast --command 'bash bin/demo.sh --car-id ${CAR_ID}'"
fi
echo "   asciinema play demo.cast"
echo "   asciinema upload demo.cast  # to share at asciinema.org"
echo "================================================================"
echo ""
