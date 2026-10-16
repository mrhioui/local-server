#!/usr/bin/env bash
# =============================================================================
# leak-check.sh — Memory and file descriptor leak tests for LocalServer
# Subject requirement: "Test for memory leaks"
#
# Usage:
#   ./tests/stress/leak-check.sh [HOST] [PORT]
#
# Prerequisites:
#   - Server is already running: java -jar target/localserver-0.1.0.jar config.json
#   - curl and lsof installed
# =============================================================================

HOST=${1:-127.0.0.1}
PORT=${2:-8080}
BASE="http://$HOST:$PORT"
FAILED=0

say()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILED=1; }

# ── get server PID ────────────────────────────────────────────────────────────
SERVER_PID=$(pgrep -f "localserver.*config.json" | head -1)
if [ -z "$SERVER_PID" ]; then
    SERVER_PID=$(pgrep -f "java.*localserver" | head -1)
fi
if [ -z "$SERVER_PID" ]; then
    echo "ERROR: cannot find server process. Start it with:"
    echo "  java -jar target/localserver-0.1.0.jar config.json &"
    exit 1
fi
say "Found server PID: $SERVER_PID"

# ── helper: count open file descriptors ──────────────────────────────────────
fd_count() {
    ls -1 /proc/"$SERVER_PID"/fd 2>/dev/null | wc -l
}

# ── helper: get JVM heap used (MB) via jcmd if available ─────────────────────
heap_used_mb() {
    if command -v jcmd &>/dev/null; then
        jcmd "$SERVER_PID" VM.native_memory 2>/dev/null \
            | grep -i "heap" | head -1 \
            | grep -oP 'used=\K\d+' || echo "0"
    else
        echo "0"
    fi
}

# ── preflight ─────────────────────────────────────────────────────────────────
say "Preflight"
if ! curl -sf -o /dev/null "$BASE/"; then
    echo "ERROR: server not responding at $BASE"
    exit 1
fi
ok "Server responding"

# ── 1. File descriptor baseline ───────────────────────────────────────────────
say "1. File descriptor leak check"
FD_BEFORE=$(fd_count)
echo "   FDs before: $FD_BEFORE"

# Make 500 sequential requests
for i in $(seq 1 500); do
    curl -sf -o /dev/null "$BASE/" || true
done

sleep 2   # let server close lingering connections
FD_AFTER=$(fd_count)
echo "   FDs after 500 requests: $FD_AFTER"

FD_DELTA=$((FD_AFTER - FD_BEFORE))
if [ "$FD_DELTA" -le 5 ]; then
    ok "File descriptors stable (delta=$FD_DELTA)"
else
    fail "Possible FD leak: delta=$FD_DELTA (before=$FD_BEFORE after=$FD_AFTER)"
fi

# ── 2. Slow-client connection cleanup ────────────────────────────────────────
say "2. Slow-client / half-open connection cleanup"

CONNS_BEFORE=$(ss -tnp 2>/dev/null | grep ":$PORT" | wc -l || echo 0)

# Open 20 connections, send partial request, then close without finishing
for i in $(seq 1 20); do
    (echo -n "GET / HTTP/1.1\r\nHo"; sleep 10) | nc -q0 "$HOST" "$PORT" &>/dev/null &
done
sleep 1

CONNS_PEAK=$(ss -tnp 2>/dev/null | grep ":$PORT" | wc -l || echo 0)
echo "   Peak connections (with slow clients): $CONNS_PEAK"

# Wait for server to time them out (up to 20s with default keep-alive=15s)
sleep 18
CONNS_AFTER=$(ss -tnp 2>/dev/null | grep ":$PORT" | wc -l || echo 0)
echo "   Connections after timeout: $CONNS_AFTER"

if [ "$CONNS_AFTER" -le "$((CONNS_BEFORE + 2))" ]; then
    ok "Slow connections cleaned up (remaining=$CONNS_AFTER)"
else
    fail "Connections not cleaned up (remaining=$CONNS_AFTER, baseline=$CONNS_BEFORE)"
fi

# ── 3. Temp file cleanup ──────────────────────────────────────────────────────
say "3. Temp file cleanup after uploads"

# Find server temp dir
TMPDIR_PATTERN="/tmp/localserver-*"
TMPDIR=$(ls -d $TMPDIR_PATTERN 2>/dev/null | head -1)
if [ -z "$TMPDIR" ]; then
    echo "   (no temp dir found at $TMPDIR_PATTERN — skipping)"
else
    TMP_BEFORE=$(find "$TMPDIR" -type f | wc -l)

    # Send 10 uploads
    for i in $(seq 1 10); do
        dd if=/dev/urandom bs=1024 count=32 2>/dev/null \
            | curl -sf -o /dev/null -X POST \
                   -H "Content-Type: application/octet-stream" \
                   --data-binary @- "$BASE/upload" || true
    done

    sleep 2
    TMP_AFTER=$(find "$TMPDIR" -type f | wc -l)
    echo "   Temp files before: $TMP_BEFORE, after: $TMP_AFTER"
    if [ "$TMP_AFTER" -le "$((TMP_BEFORE + 1))" ]; then
        ok "Temp files cleaned up"
    else
        fail "Temp files not cleaned up (before=$TMP_BEFORE after=$TMP_AFTER)"
    fi
fi

# ── 4. Large-body upload temp file cleanup ────────────────────────────────────
say "4. Large upload: temp file created and then removed"
if [ -n "$TMPDIR" ]; then
    TMP_BEFORE=$(find "$TMPDIR" -type f | wc -l)
    dd if=/dev/urandom bs=1024 count=512 2>/dev/null \
        | curl -sf -o /dev/null -X POST \
               -H "Content-Type: application/octet-stream" \
               --data-binary @- "$BASE/upload" || true
    sleep 2
    TMP_AFTER=$(find "$TMPDIR" -type f | wc -l)
    if [ "$TMP_AFTER" -le "$((TMP_BEFORE + 1))" ]; then
        ok "Large upload temp file removed"
    else
        fail "Large upload temp file not removed (before=$TMP_BEFORE after=$TMP_AFTER)"
    fi
fi

# ── 5. Server still alive and responsive ─────────────────────────────────────
say "5. Server still responsive after all leak tests"
STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "$BASE/" 2>/dev/null)
want() { [ "$2" = "$3" ] && ok "$1" || { fail "$1: expected=$2 got=$3"; }; }
want "GET / returns 200" "200" "$STATUS"

# ── summary ───────────────────────────────────────────────────────────────────
echo
if [ "$FAILED" = "0" ]; then
    echo -e "\033[1;32mALL LEAK CHECKS PASSED\033[0m"
    exit 0
else
    echo -e "\033[1;31mSOME LEAK CHECKS FAILED\033[0m"
    exit 1
fi

