#!/usr/bin/env bash
# ================================================================
# play-compat-test.sh — 玩法引擎 play SQL × 官方 TeslaMate schema 兼容性测试
#
# 用法: bin/play-compat-test.sh [--keep] [plays-dir]
#   --keep      跑完保留 play-compat-pg 容器（默认 docker rm -f 清理）
#   plays-dir   默认 <repo>/plays
#
# 原理:
#   1. 起 postgres:16-alpine 容器 play-compat-pg (host port 54329)，
#      灌 ops/teslamate-official-schema.sql（提取自 teslamate/teslamate:latest，
#      版本与刷新方法见该文件头部注释）
#   2. 灌 ops/fixture/01-fixture-data.sql（3 车 + drives/positions/charges fixture）
#   3. 遍历 plays/*/play.yaml 递归提取所有 `sql:` 字段，把命名参数
#      :car_id → 1, :tz → 'Asia/Shanghai', :start/:end → 测试时间串，
#      其余未知 :param → NULL（打 ⚠️），然后在官方 schema 上真实执行
#      （BEGIN..ROLLBACK 包裹 + 30s statement_timeout）
#   4. 全 PASS exit 0；任何 FAIL exit 1 并列出 play + 字段路径 + psql 错误
#
# 用途: 开源 teslamate-llm-bridge 目标用户跑原版 teslamate-org/teslamate；
#   prod 跑 fork (teslamate-multitenant，只动 private.tokens，public 表零改动)。
#   本脚本保证 play SQL 在原版 schema 上语法 + 列全部有效。
# ================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCHEMA_FILE="$REPO_ROOT/ops/teslamate-official-schema.sql"
FIXTURE_FILE="$REPO_ROOT/ops/fixture/01-fixture-data.sql"
PG_CONTAINER="play-compat-pg"
PG_IMAGE="postgres:16-alpine"
PG_PORT=54329
KEEP=0
PLAYS_DIR=""
CONTAINER_TOUCHED=0

for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    -h|--help) sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) PLAYS_DIR="$arg" ;;
  esac
done
PLAYS_DIR="${PLAYS_DIR:-$REPO_ROOT/plays}"

cleanup() {
  if [ "$KEEP" = 0 ] && [ "$CONTAINER_TOUCHED" = 1 ]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  # 注意: 不能写 `[ -n ... ] && rm`——条件为假时返回 1，set -e 会把 exit 0 覆盖成 1
  if [ -n "${TMP_DIR:-}" ]; then rm -rf "$TMP_DIR"; fi
}
trap cleanup EXIT

# ── 前置检查 ──────────────────────────────────────────────
command -v docker >/dev/null || { echo "❌ 需要 docker"; exit 1; }
python3 -c "import yaml" 2>/dev/null \
  || { echo "❌ 需要 python3 + pyyaml（pip3 install pyyaml）"; exit 1; }
[ -f "$SCHEMA_FILE" ] || { echo "❌ 缺 $SCHEMA_FILE（刷新方法见本脚本头注释）"; exit 1; }
[ -f "$FIXTURE_FILE" ] || { echo "❌ 缺 $FIXTURE_FILE — 请确认 ops/fixture/01-fixture-data.sql 已存在"; exit 1; }

# plays 为空 → 提示 + exit 0（不碰 docker）
if [ ! -d "$PLAYS_DIR" ] || ! ls "$PLAYS_DIR"/*/play.yaml >/dev/null 2>&1; then
  echo "ℹ️  $PLAYS_DIR 下没有 */play.yaml，无可测 play（plays/ 可能还没写），exit 0"
  exit 0
fi

# ── 1. 确保 PG 容器 + 官方 schema ─────────────────────────
pg_sql() { docker exec -i "$PG_CONTAINER" psql -U teslamate -d teslamate -v ON_ERROR_STOP=1 -q "$@"; }

CONTAINER_TOUCHED=1
if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  # 残留 stopped 容器状态不可信，删掉重建（不要反复 restart/recreate 同名 service）
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  echo "→ 起 $PG_CONTAINER ($PG_IMAGE, host port $PG_PORT)"
  # CI-only throwaway password — never reuse outside this script
  _PG_PASS="${PG_PASSWORD:-ci-only-disposable}"
  docker run -d --name "$PG_CONTAINER" \
    -e POSTGRES_USER=teslamate -e "POSTGRES_PASSWORD=${_PG_PASS}" -e POSTGRES_DB=teslamate \
    -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" >/dev/null
fi
# postgres 官方镜像首次启动会先起一个临时 server 跑 init 脚本，再关掉重启到正式端口。
# pg_isready 在临时 server 阶段就会返回 ready，紧接着的 psql 可能撞上
# "FATAL: the database system is shutting down"（init 重启窗口），在 set -e 下直接挂掉。
# 因此不能只信第一次 pg_isready：要求连续 3 次真实 `SELECT 1` 成功，确保已越过重启窗口。
ok=0
for i in $(seq 1 60); do
  if docker exec "$PG_CONTAINER" psql -U teslamate -d teslamate -tAc 'SELECT 1' >/dev/null 2>&1; then
    ok=$((ok + 1))
    [ "$ok" -ge 3 ] && break
  else
    ok=0
  fi
  [ "$i" = 60 ] && { echo "❌ PG 60s 未 ready，docker logs $PG_CONTAINER 排查"; exit 1; }
  sleep 1
done

if [ "$(docker exec "$PG_CONTAINER" psql -U teslamate -d teslamate -tAc \
        "SELECT to_regclass('public.drives') IS NOT NULL" 2>/dev/null)" != "t" ]; then
  echo "→ 灌官方 schema ($(basename "$SCHEMA_FILE"))"
  pg_sql < "$SCHEMA_FILE" >/dev/null
fi

# ── 2. 灌 fixture ─────────────────────────────────────────
echo "→ 灌 fixture ($(basename "$FIXTURE_FILE"))"
if ! pg_sql < "$FIXTURE_FILE" >/tmp/play-compat-fixture.log 2>&1; then
  echo "⚠️  fixture 灌入失败（见 /tmp/play-compat-fixture.log），继续用空表做语法/列校验"
  tail -5 /tmp/play-compat-fixture.log | sed 's/^/   /'
fi
# settings 单行实例级默认行（schema-only 库里没有，teslamate init 才会建）
pg_sql -c "INSERT INTO public.settings (inserted_at, updated_at)
           SELECT now(), now() WHERE NOT EXISTS (SELECT 1 FROM public.settings);" >/dev/null

# ── 3. 提取器（python3 + pyyaml）──────────────────────────
TMP_DIR="$(mktemp -d /tmp/play-compat.XXXXXX)"
EXTRACTOR="$TMP_DIR/extract_sql.py"
cat > "$EXTRACTOR" <<'PYEOF'
"""从 play.yaml 递归提取全部 sql 字段，做参数替换，写 <outdir>/<i>.sql + manifest.tsv"""
import os, re, sys
import yaml

PARAMS = {
    "car_id": "1",
    "tz": "'Asia/Shanghai'",
    "start": "'2024-01-01 00:00:00'",
    "end": "'2026-12-31 23:59:59'",
}

def substitute(sql):
    unknown = []
    def repl(m):
        name = m.group(1)
        if name in PARAMS:
            return PARAMS[name]
        unknown.append(name)
        return "NULL"
    # (?<![:\w]) 避开 ::cast、字串里的 word:tail / 12:30
    out = re.sub(r"(?<![:\w]):([a-zA-Z_][a-zA-Z0-9_]*)", repl, sql)
    return out, sorted(set(unknown))

def walk(node, path, found):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "sql" and isinstance(v, str):
                found.append((".".join(path + [k]), v))
            else:
                walk(v, path + [str(k)], found)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + [str(i)], found)

def main():
    yml, outdir = sys.argv[1], sys.argv[2]
    with open(yml, encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    found = []
    walk(doc, [], found)
    rows = []
    for i, (keypath, sql) in enumerate(found):
        substituted, unknown = substitute(sql)
        with open(os.path.join(outdir, f"{i}.sql"), "w", encoding="utf-8") as f:
            f.write(substituted)
        rows.append(f"{i}\t{keypath}\t{','.join(unknown)}")
    with open(os.path.join(outdir, "manifest.tsv"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(r + "\n")

main()
PYEOF

# ── 4. 遍历 plays 执行 ────────────────────────────────────
pass_n=0; fail_n=0; skip_n=0
echo
echo "===== play SQL × 官方 TeslaMate schema 兼容性 ====="
for yml in "$PLAYS_DIR"/*/play.yaml; do
  play_name="$(basename "$(dirname "$yml")")"
  workdir="$TMP_DIR/$play_name"
  mkdir -p "$workdir"

  if ! python3 "$EXTRACTOR" "$yml" "$workdir" 2>"$workdir/extract.err"; then
    echo "[FAIL] $play_name · YAML 解析/提取失败:"
    sed 's/^/       /' "$workdir/extract.err"
    fail_n=$((fail_n + 1))
    continue
  fi
  if [ ! -s "$workdir/manifest.tsv" ]; then
    echo "[SKIP] $play_name · 无 sql 字段"
    skip_n=$((skip_n + 1))
    continue
  fi

  while IFS=$'\t' read -r idx keypath unknown; do
    wrapped="$workdir/$idx.wrapped.sql"
    {
      echo "BEGIN;"
      echo "SET LOCAL statement_timeout = '30s';"
      cat "$workdir/$idx.sql"
      echo ";"
      echo "ROLLBACK;"
    } > "$wrapped"
    warn=""
    [ -n "$unknown" ] && warn="  ⚠️ 未知参数→NULL: $unknown"
    if err="$(pg_sql < "$wrapped" 2>&1 >/dev/null)"; then
      echo "[PASS] $play_name · $keypath$warn"
      pass_n=$((pass_n + 1))
    else
      echo "[FAIL] $play_name · $keypath$warn"
      echo "$err" | sed 's/^/       /'
      fail_n=$((fail_n + 1))
    fi
  done < "$workdir/manifest.tsv"
done

# ── 5. 汇总 ───────────────────────────────────────────────
echo
echo "===== 汇总: $pass_n PASS / $fail_n FAIL / $skip_n SKIP ====="
if [ "$fail_n" -gt 0 ]; then
  echo "❌ 有 play SQL 在官方 TeslaMate schema 上跑不过，见上方 [FAIL] 明细"
  exit 1
fi
echo "✅ 全部 play SQL 在官方 TeslaMate schema (teslamate/teslamate:latest) 上有效"
