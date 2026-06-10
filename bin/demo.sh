#!/usr/bin/env bash
# ================================================================
# demo.sh — teslamate-llm-bridge end-to-end demo
#
# Walks through the complete flow:
#   1. Start the bridge (docker compose up)
#   2. Wait for health
#   3. List plays
#   4. Run driving-personality and display the result
#   5. Download the share card PNG
#   6. Print asciinema recording hint
#
# Usage:
#   bash bin/demo.sh [--car-id N]
#
#   --car-id N   TeslaMate car_id to use (default: 1)
#
# Pre-requisites:
#   - .env file exists with TM_DB_* set (copy from .env.example)
#   - Docker Desktop running
# ================================================================

set -euo pipefail

CAR_ID=1
while [[ $# -gt 0 ]]; do
  case $1 in
    --car-id) CAR_ID="$2"; shift 2;;
    *) echo "Unknown arg: $1" >&2; exit 1;;
  esac
done

BASE_URL="http://localhost:8770"
AUTH_HEADER=""

# Pick up API_TOKEN from .env if present
if [[ -f .env ]]; then
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
echo "================================================================"
echo ""

# ------------------------------------------------------------------
echo ">>> [1/5] Starting bridge (docker compose up -d)..."
docker compose up -d
echo "    Bridge starting — waiting up to 60s for health..."

for i in $(seq 1 30); do
  if _curl "${BASE_URL}/actuator/health" -o /dev/null 2>/dev/null; then
    echo "    Health: UP (after ~$((i*2))s)"
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "    ERROR: Bridge did not become healthy within 60s"
    docker compose logs bridge | tail -30
    exit 1
  fi
  sleep 2
done

# ------------------------------------------------------------------
echo ""
echo ">>> [2/5] Listing available plays..."
PLAYS_JSON=$(_curl "${BASE_URL}/api/v1/plays")
echo "$PLAYS_JSON" | python3 -m json.tool 2>/dev/null || echo "$PLAYS_JSON"

# ------------------------------------------------------------------
echo ""
echo ">>> [3/5] Running driving-personality (car_id=${CAR_ID}, window=90 days)..."
START_DATE=$(date -v-90d '+%Y-%m-%d' 2>/dev/null || date -d '90 days ago' '+%Y-%m-%d' 2>/dev/null || echo "2024-01-01")
RESULT=$(_curl "${BASE_URL}/api/v1/cars/${CAR_ID}/play/driving-personality?start_date=${START_DATE}")
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

# Extract persona name for display
PERSONA=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); p=d.get('data',{}).get('persona',{}); print(p.get('name','(no persona — try a wider window)'))" 2>/dev/null || echo "(could not parse persona)")
echo ""
echo "    Persona: ${PERSONA}"

# ------------------------------------------------------------------
echo ""
echo ">>> [4/5] Downloading share card PNG (car_id=${CAR_ID})..."
CARD_PATH="/tmp/driving-personality-demo.png"
if _curl "${BASE_URL}/api/v1/cars/${CAR_ID}/play/driving-personality/card.png" -o "${CARD_PATH}"; then
  BYTES=$(wc -c < "${CARD_PATH}" | tr -d ' ')
  echo "    Card saved: ${CARD_PATH} (${BYTES} bytes)"
  if command -v open &>/dev/null; then
    echo "    Opening card with system viewer..."
    open "${CARD_PATH}"
  elif command -v xdg-open &>/dev/null; then
    xdg-open "${CARD_PATH}"
  fi
else
  echo "    Card render failed — 'scored: false'? Try --car-id with another car or a wider date range."
fi

# ------------------------------------------------------------------
echo ""
echo ">>> [5/5] Done!"
echo ""
echo "================================================================"
echo " Next steps:"
echo "   - Connect to Claude Desktop (MCP):  docs/connect-claude-mcp.md"
echo "   - Connect to ChatGPT Actions:        docs/connect-chatgpt.md"
echo "   - Connect to Coze:                   docs/connect-coze.md"
echo "   - Add a new play:                    AGENTS.md"
echo ""
echo " To record an asciinema screencast of this demo:"
echo "   asciinema rec demo.cast --command 'bash bin/demo.sh --car-id ${CAR_ID}'"
echo "   asciinema play demo.cast"
echo "   asciinema upload demo.cast  # to share at asciinema.org"
echo "================================================================"
echo ""
