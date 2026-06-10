#!/usr/bin/env python3
"""validate_plays.py — engine-mirror validation + fixture runner for plays/.

This is the script behind ``.github/workflows/validate-plays.yml`` and the
"fixtures drive CI" promise in README/AGENTS. It mirrors the bridge engine's
load-time gates and compute-pipeline semantics (``PlayLoader`` /
``PlaySqlGuard`` / ``PlayExpr`` / ``PlayComputeEngine``) closely enough that a
play passing here also loads and computes identically in production:

  1. structural checks      — name == folder, fixtures.yaml present + non-empty
  2. SQL static guard       — tenant filter ``car_id = :car_id`` (comments
                              stripped first), SELECT/WITH only, no ';',
                              keyword blacklist
  3. compute checks         — step shape, expr compilation, level catch-all,
                              lookup table + required default, no card-only
                              built-ins in compute templates
  4. card template lint     — forbidden content, well-formed XML, href/src and
                              url() refs local-#-only, no non-BMP code points,
                              every ${var} resolves
  5. fixture execution      — each fixture row runs through min_sample + the
                              full compute pipeline; ``expect`` values asserted
                              (numbers within ±0.001), ``expect_unscored`` cases
                              must trip the min_sample gate

JSON-Schema validation (plays/play.schema.json) runs as a separate CI step via
ajv; this script enforces the *semantic* rules the schema cannot express.

Exit code 0 = all plays pass; 1 = at least one failure (details on stderr).
"""

from __future__ import annotations

import math
import re
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
PLAYS_DIR = REPO_ROOT / "plays"

NAME_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")
VAR_RE = re.compile(r"^[a-z_][a-z0-9_]*$")
COMPUTE_PLACEHOLDER_RE = re.compile(r"\$\{([a-z_][a-z0-9_]*)}")
CARD_PLACEHOLDER_RE = re.compile(r"\$\{([^}]*)}")
DOTTED_PATH_RE = re.compile(r"^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*$")

# --- SQL guard (mirror of PlaySqlGuard) -------------------------------------
SQL_COMMENTS_RE = re.compile(r"--[^\n]*|/\*.*?\*/", re.DOTALL)
SQL_FORBIDDEN_RE = re.compile(
    r"\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|COPY|DO|CALL|PG_SLEEP)\b",
    re.IGNORECASE,
)
CAR_ID_FILTER_RE = re.compile(r"(?<!:)\bcar_id\s*=\s*:car_id\b", re.IGNORECASE)

# --- card lint (mirror of PlayLoader.lintTemplate) ---------------------------
TEMPLATE_FORBIDDEN = ["<script", "<foreignobject", "<!entity", "<!doctype"]
URL_REF_RE = re.compile(r"url\s*\(\s*[\"']?\s*([^)\"']*)", re.IGNORECASE)
BUILTIN_TEMPLATE_VARS = {"car_name", "car_model", "window_label", "generated_at", "window_days", "watermark"}
CARD_ONLY_BUILTINS = {"car_name", "car_model", "window_label", "generated_at"}


class PlayError(Exception):
    pass


# ============================================================================
# expr — mirror of PlayExpr (recursive descent; + - * / parens, unary minus,
# numeric literals, identifiers, GREATEST/LEAST varargs, ROUND 1-2 args;
# division by zero -> 0; ROUND is half-up like Java Math.round)
# ============================================================================

TOKEN_RE = re.compile(
    r"\s*(?:(?P<num>\d[\d.]*|\.\d+)|(?P<ident>[a-z_][a-z0-9_]*)|(?P<func>[A-Z]+)"
    r"|(?P<op>[+\-*/])|(?P<lp>\()|(?P<rp>\))|(?P<comma>,))"
)


def tokenize(src: str):
    out, i = [], 0
    while i < len(src):
        m = TOKEN_RE.match(src, i)
        if not m or m.end() == i:
            if src[i:].strip() == "":
                break
            raise PlayError(f"expr: illegal character at '{src[i:]}'")
        i = m.end()
        kind = m.lastgroup
        text = m.group(kind)
        if kind == "num":
            try:
                float(text)
            except ValueError:
                raise PlayError(f"expr: bad numeric literal '{text}'") from None
        if kind == "func" and text not in ("GREATEST", "LEAST", "ROUND"):
            raise PlayError(f"expr: unknown function '{text}' (only GREATEST/LEAST/ROUND)")
        out.append((kind, text))
    return out


class ExprParser:
    def __init__(self, src: str):
        self.src = src
        self.tokens = tokenize(src)
        self.pos = 0

    def peek(self):
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def next(self):
        if self.pos >= len(self.tokens):
            raise PlayError(f"expr ended unexpectedly: {self.src}")
        t = self.tokens[self.pos]
        self.pos += 1
        return t

    def parse(self):
        node = self.parse_expr()
        if self.pos < len(self.tokens):
            raise PlayError(f"expr: trailing token '{self.tokens[self.pos][1]}' in: {self.src}")
        return node

    def parse_expr(self):
        left = self.parse_term()
        while self.peek() and self.peek()[0] == "op" and self.peek()[1] in "+-":
            op = self.next()[1]
            left = ("bin", op, left, self.parse_term())
        return left

    def parse_term(self):
        left = self.parse_unary()
        while self.peek() and self.peek()[0] == "op" and self.peek()[1] in "*/":
            op = self.next()[1]
            left = ("bin", op, left, self.parse_unary())
        return left

    def parse_unary(self):
        if self.peek() and self.peek()[0] == "op" and self.peek()[1] == "-":
            self.next()
            return ("neg", self.parse_unary())
        return self.parse_primary()

    def parse_primary(self):
        kind, text = self.next()
        if kind == "num":
            return ("num", float(text))
        if kind == "ident":
            return ("ident", text)
        if kind == "func":
            if self.next()[0] != "lp":
                raise PlayError(f"expr: expected '(' after {text}")
            args = [self.parse_expr()]
            while self.peek() and self.peek()[0] == "comma":
                self.next()
                args.append(self.parse_expr())
            if self.next()[0] != "rp":
                raise PlayError("expr: expected ')'")
            if text == "ROUND" and len(args) > 2:
                raise PlayError("expr: ROUND takes at most 2 args")
            return ("func", text, args)
        if kind == "lp":
            inner = self.parse_expr()
            if self.next()[0] != "rp":
                raise PlayError("expr: expected ')'")
            return inner
        raise PlayError(f"expr: illegal token '{text}'")


def java_round(v: float) -> float:
    # Java Math.round == floor(x + 0.5)
    return float(math.floor(v + 0.5))


def eval_expr(node, ctx):
    kind = node[0]
    if kind == "num":
        return node[1]
    if kind == "ident":
        name = node[1]
        if name not in ctx:
            raise PlayError(f"unknown identifier '{name}'")
        v = ctx[name]
        if v is None:
            return 0.0  # engine: SQL NULL -> 0 + WARN
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            raise PlayError(f"identifier '{name}' is not numeric")
        return float(v)
    if kind == "neg":
        return -eval_expr(node[1], ctx)
    if kind == "bin":
        _, op, l, r = node
        a, b = eval_expr(l, ctx), eval_expr(r, ctx)
        if op == "+":
            return a + b
        if op == "-":
            return a - b
        if op == "*":
            return a * b
        if b == 0.0:
            return 0.0  # engine: division by zero -> 0 + WARN
        return a / b
    if kind == "func":
        _, fn, args = node
        vals = [eval_expr(a, ctx) for a in args]
        if fn == "GREATEST":
            return max(vals)
        if fn == "LEAST":
            return min(vals)
        # ROUND
        if len(vals) == 1:
            return java_round(vals[0])
        scale = 10 ** int(vals[1])
        return java_round(vals[0] * scale) / scale
    raise PlayError(f"bad node {node}")


def format_value(v) -> str:
    """Mirror of PlayComputeEngine.formatValue: integral numbers drop '.0'."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, (int, float)):
        d = float(v)
        if d == math.floor(d) and not math.isinf(d):
            return str(int(d))
        return format(Decimal(repr(d)).normalize(), "f")
    return str(v)


# ============================================================================
# manifest semantic validation (rules JSON Schema cannot express)
# ============================================================================


def check_sql(sql: str):
    if not sql or not sql.strip():
        raise PlayError("sql is empty")
    if ";" in sql:
        raise PlayError("sql must not contain ';'")
    stripped = SQL_COMMENTS_RE.sub(" ", sql).strip()
    if not CAR_ID_FILTER_RE.search(stripped):
        raise PlayError(
            "sql must contain a real tenant-filter comparison 'car_id = :car_id' "
            "(comments don't count; a projected/trivially-true :car_id doesn't count)"
        )
    upper = stripped.upper()
    if not (upper.startswith("SELECT") or upper.startswith("WITH")):
        raise PlayError("sql must start with SELECT or WITH after comment stripping")
    m = SQL_FORBIDDEN_RE.search(stripped)
    if m:
        raise PlayError(f"sql contains forbidden keyword '{m.group(1)}'")


def check_compute(compute: list, tables: dict):
    """Returns the ordered list of (var, kind, payload) steps."""
    steps, seen = [], set()
    for step in compute:
        var = step.get("var")
        if not isinstance(var, str) or not VAR_RE.match(var):
            raise PlayError(f"compute var '{var}' invalid")
        if var in seen:
            raise PlayError(f"compute var '{var}' duplicated")
        seen.add(var)
        kinds = [k for k in ("expr", "level", "template", "lookup") if k in step]
        if len(kinds) != 1:
            raise PlayError(f"compute var '{var}' must have exactly one of expr/level/template/lookup")
        kind = kinds[0]
        payload = step[kind]
        if kind == "expr":
            payload = ExprParser(payload).parse()
        elif kind == "level":
            thresholds = payload.get("thresholds") or []
            if not thresholds:
                raise PlayError(f"level('{var}') needs thresholds")
            for i, t in enumerate(thresholds):
                last = i == len(thresholds) - 1
                if last and "lt" in t:
                    raise PlayError(f"level('{var}') last threshold must omit 'lt' (catch-all)")
                if not last and not isinstance(t.get("lt"), (int, float)):
                    raise PlayError(f"level('{var}') non-last threshold needs numeric 'lt'")
        elif kind == "template":
            for ident in COMPUTE_PLACEHOLDER_RE.findall(payload):
                if ident in CARD_ONLY_BUILTINS:
                    raise PlayError(
                        f"compute template('{var}') references '${{{ident}}}' — that built-in "
                        "is card-only (not present in the compute context); see spec §5"
                    )
        elif kind == "lookup":
            table = payload.get("table")
            if table not in tables:
                raise PlayError(f"lookup('{var}') references unknown table '{table}'")
            if not isinstance(payload.get("default"), dict):
                raise PlayError(f"lookup('{var}') requires a 'default' object (engine-enforced)")
        steps.append((var, kind, payload))
    return steps


def check_card_template(template: str, known_vars: set):
    lower = template.lower()
    for forbidden in TEMPLATE_FORBIDDEN:
        if forbidden in lower:
            raise PlayError(f"card.svg.tmpl contains forbidden content '{forbidden}'")
    for ch in template:
        if ord(ch) > 0xFFFF:
            raise PlayError(
                f"card.svg.tmpl contains non-BMP code point '{ch}' (U+{ord(ch):X}) — "
                "render host has no emoji font; put emoji in the manifest 'emoji:' field"
            )
    try:
        root = ET.fromstring(template)
    except ET.ParseError as e:
        raise PlayError(f"card.svg.tmpl is not well-formed XML: {e}") from None
    for el in root.iter():
        for attr, value in el.attrib.items():
            local = attr.rsplit("}", 1)[-1].lower()
            if local in ("href", "src") and not value.strip().startswith("#"):
                raise PlayError(
                    f"card.svg.tmpl <{el.tag.rsplit('}', 1)[-1]}> {local}='{value}' — only "
                    "local '#id' references are allowed (no external scheme of any kind)"
                )
    for m in URL_REF_RE.finditer(template):
        ref = m.group(1).strip()
        if not ref.startswith("#"):
            raise PlayError(f"card.svg.tmpl url({ref}) — only url(#id) references are allowed")
    for m in CARD_PLACEHOLDER_RE.finditer(template):
        path = m.group(1)
        if not DOTTED_PATH_RE.match(path):
            raise PlayError(f"card.svg.tmpl placeholder '${{{path}}}' is invalid")
        if path.split(".", 1)[0] not in known_vars:
            raise PlayError(f"card.svg.tmpl placeholder '${{{path}}}' does not resolve")


# ============================================================================
# fixture runner — mirror of PlayComputeEngine.run + PlayRegistryTest.runFixture
# ============================================================================


def run_pipeline(steps, tables, row: dict, window_days: float) -> dict:
    ctx = {str(k).lower(): v for k, v in row.items()}
    ctx["window_days"] = float(window_days)
    for var, kind, payload in steps:
        if kind == "expr":
            ctx[var] = eval_expr(payload, ctx)
        elif kind == "level":
            raw = ctx.get(payload["input"])
            if payload["input"] not in ctx:
                raise PlayError(f"level('{var}') input '{payload['input']}' unknown")
            if isinstance(raw, bool) or not isinstance(raw, (int, float)):
                raise PlayError(f"level('{var}') input '{payload['input']}' is not numeric")
            v = float(raw)
            for t in payload["thresholds"]:
                if "lt" not in t or v < t["lt"]:
                    ctx[var] = t["label"]
                    break
        elif kind == "template":

            def repl(m):
                ident = m.group(1)
                if ident not in ctx:
                    raise PlayError(f"template('{var}') unknown identifier '{ident}'")
                return format_value(ctx[ident])

            ctx[var] = COMPUTE_PLACEHOLDER_RE.sub(repl, payload)
        elif kind == "lookup":
            key_var = payload["key"]
            if key_var not in ctx:
                raise PlayError(f"lookup('{var}') key '{key_var}' unknown")
            key = ctx[key_var]
            if not isinstance(key, str):
                raise PlayError(f"lookup('{var}') key '{key_var}' is not a string")
            hit = (tables.get(payload["table"]) or {}).get(key)
            if hit is None:
                hit = {k: str(v) for k, v in payload["default"].items()}
            ctx[var] = hit
    return ctx


def resolve_dotted(ctx, path: str):
    cur = ctx
    for seg in path.split("."):
        if not isinstance(cur, dict) or seg not in cur:
            raise PlayError(f"expect path '{path}' does not resolve (missing '{seg}')")
        cur = cur[seg]
    return cur


def run_fixtures(play_name, steps, tables, manifest, fixtures: list):
    ms_field = manifest["min_sample"]["field"].lower()
    ms_min = manifest["min_sample"]["min"]
    has_unscored = False
    has_scored = False
    for fx in fixtures:
        name = fx.get("name", "<unnamed>")
        row = fx.get("row") or {}
        window_days = fx.get("window_days", 30)
        sample = row.get(ms_field, 0) or 0
        if fx.get("expect_unscored") is True:
            has_unscored = True
            if not sample < ms_min:
                raise PlayError(
                    f"fixture '{name}': expect_unscored but row.{ms_field}={sample} >= min {ms_min}"
                )
            continue
        has_scored = True
        if sample < ms_min:
            raise PlayError(
                f"fixture '{name}': row.{ms_field}={sample} < min {ms_min} but no expect_unscored"
            )
        expect = fx.get("expect")
        if not isinstance(expect, dict) or not expect:
            raise PlayError(f"fixture '{name}': missing non-empty 'expect' map")
        ctx = run_pipeline(steps, tables, row, window_days)
        for key, want in expect.items():
            got = resolve_dotted(ctx, key)
            if isinstance(want, bool):
                raise PlayError(
                    f"fixture '{name}': boolean expect '{key}' unsupported — use "
                    "'expect_unscored: true' at fixture level for min_sample cases"
                )
            if isinstance(want, (int, float)):
                if isinstance(got, bool) or not isinstance(got, (int, float)):
                    raise PlayError(f"fixture '{name}': '{key}' expected number, got {got!r}")
                if abs(float(got) - float(want)) > 0.001:
                    raise PlayError(f"fixture '{name}': '{key}' expected {want}, got {got}")
            else:
                if str(got) != str(want):
                    raise PlayError(f"fixture '{name}': '{key}' expected '{want}', got '{got}'")
    if not has_unscored:
        raise PlayError("fixtures must include >=1 'expect_unscored: true' case (spec §8)")
    if not has_scored:
        raise PlayError("fixtures must include >=1 scored case with 'expect' (spec §8)")


# ============================================================================


def validate_play(play_dir: Path):
    manifest_path = play_dir / "play.yaml"
    if not manifest_path.is_file():
        raise PlayError("play.yaml missing")
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise PlayError("play.yaml root must be a mapping")

    name = manifest.get("name")
    if name != play_dir.name:
        raise PlayError(f"name '{name}' != folder name '{play_dir.name}'")
    if not NAME_RE.match(str(name)):
        raise PlayError(f"name '{name}' does not match {NAME_RE.pattern}")

    check_sql(manifest.get("sql", ""))

    tables = manifest.get("tables") or {}
    steps = check_compute(manifest.get("compute") or [], tables)

    output_fields = (manifest.get("output") or {}).get("fields") or []
    known_vars = set(BUILTIN_TEMPLATE_VARS)
    known_vars.update(var for var, _, _ in steps)
    known_vars.update(f["name"] for f in output_fields)

    card = manifest.get("card")
    if card is not None:
        tmpl_path = play_dir / "card.svg.tmpl"
        if not tmpl_path.is_file():
            raise PlayError("manifest declares card but card.svg.tmpl is missing")
        check_card_template(tmpl_path.read_text(encoding="utf-8"), known_vars)

    fixtures_path = play_dir / "fixtures.yaml"
    if not fixtures_path.is_file():
        raise PlayError("fixtures.yaml missing (required — spec §8)")
    fixtures_doc = yaml.safe_load(fixtures_path.read_text(encoding="utf-8")) or {}
    fixtures = fixtures_doc.get("fixtures")
    if not isinstance(fixtures, list) or not fixtures:
        hint = " (found top-level 'cases:' — rename to 'fixtures:')" if "cases" in fixtures_doc else ""
        raise PlayError(f"fixtures.yaml needs a non-empty top-level 'fixtures:' list{hint}")
    run_fixtures(name, steps, tables, manifest, fixtures)


def main() -> int:
    if not PLAYS_DIR.is_dir():
        print(f"no plays/ directory at {PLAYS_DIR}", file=sys.stderr)
        return 1
    play_dirs = sorted(
        d for d in PLAYS_DIR.iterdir() if d.is_dir() and not d.name.startswith(("_", "."))
    )
    if not play_dirs:
        print("no plays found — nothing to validate")
        return 0
    failures = 0
    for d in play_dirs:
        try:
            validate_play(d)
            print(f"[PASS] {d.name}")
        except PlayError as e:
            failures += 1
            print(f"[FAIL] {d.name}: {e}", file=sys.stderr)
    print(f"===== {len(play_dirs) - failures} PASS / {failures} FAIL =====")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
