#!/usr/bin/env bash
# =============================================================================
# stress-test.sh — Siege stress test for LocalServer
# Subject requirement: target 99.5% availability
#
# Usage:
#   ./tests/stress/stress-test.sh [HOST] [PORT]
#   ./tests/stress/stress-test.sh 127.0.0.1 8080
#
# Prerequisites:
#   - Server is already running: java -jar target/localserver-0.1.0.jar config.json
#   - siege is installed: sudo apt install siege  OR  brew install siege
# =============================================================================

HOST=${1:-127.0.0.1}
PORT=${2:-8080}
BASE="http://$HOST:$PORT"
FAILED=0
SIEGE_DURATION=${SIEGE_DURATION:-30S}   # duration of each siege run
SIEGE_CONCURRENCY=${SIEGE_CONCURRENCY:-25}

# ── helpers ───────────────────────────────────────────────────────────────────
say()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILED=1; }
want() {   # want <label> <expected> <actual>
    if [ "$2" = "$3" ]; then ok "$1 (got $3)"; else fail "$1: expected=$2 actual=$3"; fi
}

check_siege() {
    if ! command -v siege &>/dev/null; then
        echo "siege not found — install with: sudo apt install siege"
        exit 1
    fi
}

run_siege() {   # run_siege <label> <url> [extra siege args]
    local label="$1"; local url="$2"; shift 2
    local out
    out=$(siege -b -t "$SIEGE_DURATION" -c "$SIEGE_CONCURRENCY" "$@" "$url" 2>&1)
    local avail
    avail=$(echo "$out" | grep -i 'availability' | grep -oP '[\d.]+(?=\s*%)')
    if [ -z "$avail" ]; then
        fail "$label — siege output missing availability (siege may have failed)"
        echo "$out" | tail -20
        return
    fi
    # availability must be >= 99.5
    local pass
    pass=$(echo "$avail >= 99.5" | bc -l 2>/dev/null || python3 -c "print(1 if $avail >= 99.5 else 0)")
    if [ "$pass" = "1" ]; then
        ok "$label — availability=${avail}% (>= 99.5%)"
    else
        fail "$label — availability=${avail}% (< 99.5%)"
    fi
}

# ── preflight: server must be up ──────────────────────────────────────────────
say "Preflight check"
if ! curl -sf -o /dev/null "$BASE/"; then
    echo "ERROR: server not responding at $BASE — start it first:"
    echo "  java -jar target/localserver-0.1.0.jar config.json"
    exit 1
fi
ok "Server is up at $BASE"

check_siege

# ── 1. Static GET stress ───────────────────────────────────────────────────────
say "1. GET / — static file stress (${SIEGE_DURATION}, ${SIEGE_CONCURRENCY} concurrent)"
run_siege "GET /" "$BASE/"

# ── 2. Upload stress (POST) ────────────────────────────────────────────────────
say "2. POST /upload — upload stress"
TMP_FILE=$(mktemp)
dd if=/dev/urandom of="$TMP_FILE" bs=1024 count=64 2>/dev/null
run_siege "POST /upload" "$BASE/upload" --content-type "application/octet-stream" --data "@$TMP_FILE"
rm -f "$TMP_FILE"

# ── 3. 404 pages ──────────────────────────────────────────────────────────────
say "3. GET /nonexistent — 404 error page stress"
run_siege "GET 404" "$BASE/this-does-not-exist-at-all"

# ── 4. Multi-URL (simulates real traffic) ─────────────────────────────────────
say "4. Mixed URLs — realistic traffic pattern"
URLS_FILE=$(mktemp)
cat > "$URLS_FILE" << EOF
$BASE/
$BASE/
$BASE/upload
$BASE/this-is-404
EOF
run_siege "Mixed traffic" -f "$URLS_FILE"
rm -f "$URLS_FILE"

# ── summary ───────────────────────────────────────────────────────────────────
echo
if [ "$FAILED" = "0" ]; then
    echo -e "\033[1;32mALL STRESS TESTS PASSED (>= 99.5% availability)\033[0m"
    exit 0
else
    echo -e "\033[1;31mSOME STRESS TESTS FAILED\033[0m"
    exit 1
fi

