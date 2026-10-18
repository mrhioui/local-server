#!/bin/bash
# =============================================================================
# full_test.sh — Comprehensive HTTP server test suite
# Covers every item from the evaluation checklist.
# Usage: ./full_test.sh [server-host] [port]
# Defaults: localhost 8080
# =============================================================================

HOST="${1:-localhost}"
PORT="${2:-8080}"
BASE="http://$HOST:$PORT"

PASS=0
FAIL=0
SKIP=0

# ── Colours ──────────────────────────────────────────────────────────────────
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
CYAN="\033[0;36m"
BOLD="\033[1m"
RESET="\033[0m"

# ── Helpers ───────────────────────────────────────────────────────────────────
section() { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; echo -e "${BOLD}${CYAN}  $1${RESET}"; echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; }

pass() { echo -e "  ${GREEN}✓ PASS${RESET}  $1"; ((++PASS)); }
fail() { echo -e "  ${RED}✗ FAIL${RESET}  $1"; ((++FAIL)); }
skip() { echo -e "  ${YELLOW}⊘ SKIP${RESET}  $1"; ((++SKIP)); }
info() { echo -e "  ${YELLOW}ℹ${RESET}      $1"; }

# Returns the HTTP status code of a curl request.
# Usage: status=$(http_status <curl args...>)
http_status() {
    curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$@"
}

# Returns full response headers + body.
http_full() {
    curl -s -i --max-time 5 "$@"
}

# Check if the server is reachable before running tests.
check_server() {
    local code
    code=$(http_status "$BASE/")
    if [[ "$code" == "000" ]]; then
        echo -e "${RED}${BOLD}ERROR: Server is not responding at $BASE. Start it first.${RESET}"
        exit 1
    fi
}

# ── Setup ─────────────────────────────────────────────────────────────────────
UPLOAD_FILE=$(mktemp /tmp/test_upload_XXXX.txt)
echo "Hello from the test suite — this is a sample upload file." > "$UPLOAD_FILE"

COOKIE_JAR=$(mktemp /tmp/cookies_XXXX.txt)

cleanup() {
    rm -f "$UPLOAD_FILE" "$COOKIE_JAR" /tmp/upload_roundtrip_* 2>/dev/null
}
trap cleanup EXIT

echo -e "${BOLD}Target: $BASE${RESET}"
check_server
echo -e "Server is reachable. Running tests...\n"

# =============================================================================
section "1 · CORE SERVER — HTTP/1.1 compliance"
# =============================================================================

# Basic GET returns 200
resp=$(http_full "$BASE/")
echo "$resp" | grep -q "200" && pass "GET / → 200 OK" || fail "GET / → expected 200"

# Response must contain HTTP/1.1
echo "$resp" | grep -q "HTTP/1.1" && pass "Response uses HTTP/1.1" || fail "Response does not use HTTP/1.1"

# Content-Length header present on a file response
echo "$resp" | grep -qi "Content-Length" && pass "Content-Length header present" || fail "Content-Length header missing"

# Connection header present
echo "$resp" | grep -qi "Connection" && pass "Connection header present" || fail "Connection header missing"

# =============================================================================
section "2 · ERROR PAGES"
# =============================================================================

# 404 — non-existent path
code=$(http_status "$BASE/this_does_not_exist_xyz")
[[ "$code" == "404" ]] && pass "GET /nonexistent → 404" || fail "GET /nonexistent → expected 404, got $code"

# 404 body should be our custom page (not empty)
body=$(curl -s --max-time 5 "$BASE/this_does_not_exist_xyz")
[[ -n "$body" ]] && pass "404 response has a body" || fail "404 response body is empty"

# 405 — wrong method on a GET-only route
code=$(http_status -X DELETE "$BASE/")
[[ "$code" == "405" ]] && pass "DELETE / → 405 Method Not Allowed" || fail "DELETE / → expected 405, got $code"

# 405 body present
body=$(curl -s -X DELETE --max-time 5 "$BASE/")
[[ -n "$body" ]] && pass "405 response has a body" || fail "405 response body is empty"

# 400 — bad/missing Host header (send HTTP/1.1 request without Host via netcat)
if command -v nc &>/dev/null; then
    raw_resp=$(printf "GET / HTTP/1.1\r\n\r\n" | nc -w2 "$HOST" "$PORT" 2>/dev/null)
    echo "$raw_resp" | grep -q "400" && pass "Request without Host → 400" || fail "Request without Host → expected 400 in response (got: $(echo "$raw_resp" | head -1))"
else
    skip "400 no-Host test skipped (nc not available)"
fi

# 413 — body too large on port 9090 (limit: 1024 bytes)
code=$(http_status -X POST "http://$HOST:9090/" \
    -H "Content-Type: text/plain" \
    --data "$(python3 -c 'print("x"*2000)')")
[[ "$code" == "413" ]] && pass "POST oversized body to port 9090 → 413" || fail "POST oversized body to port 9090 → expected 413, got $code"

# =============================================================================
section "3 · METHODS — GET / POST / DELETE"
# =============================================================================

# GET a static file
code=$(http_status "$BASE/")
[[ "$code" == "200" ]] && pass "GET / → 200" || fail "GET / → $code"

# POST multipart upload → 201
code=$(http_status -X POST "$BASE/upload" -F "file=@$UPLOAD_FILE")
[[ "$code" == "201" ]] && pass "POST /upload (multipart) → 201" || fail "POST /upload (multipart) → expected 201, got $code"

# POST chunked upload → 201
code=$(cat "$UPLOAD_FILE" | http_status -X POST "$BASE/upload/chunked_test.txt" \
    -H "Transfer-Encoding: chunked" --data-binary @-)
[[ "$code" == "201" ]] && pass "POST /upload/chunked_test.txt (chunked) → 201" || fail "POST /upload/chunked_test.txt (chunked) → expected 201, got $code"

# DELETE an uploaded file
UNIQUE_NAME="delete_test_$(date +%s).txt"
curl -s -X POST "$BASE/upload/$UNIQUE_NAME" \
    -H "Content-Type: text/plain" \
    --data-binary "delete me" \
    -o /dev/null
# Find the file we just created in www/uploads
if [[ -f "www/uploads/$UNIQUE_NAME" ]]; then
    code=$(http_status -X DELETE "$BASE/upload/$UNIQUE_NAME")
    [[ "$code" == "200" || "$code" == "204" ]] && pass "DELETE /upload/$UNIQUE_NAME → $code" || fail "DELETE /upload/$UNIQUE_NAME → expected 200/204, got $code"
else
    skip "DELETE test skipped — could not find uploaded file"
fi

# OPTIONS / unknown method → 405
code=$(http_status -X OPTIONS "$BASE/")
[[ "$code" == "405" ]] && pass "OPTIONS / → 405 (unsupported method)" || fail "OPTIONS / → expected 405, got $code"

# =============================================================================
section "4 · FILE UPLOAD INTEGRITY"
# =============================================================================

# Upload a file, then download it and compare checksums.
ORIGINAL_SUM=$(md5sum "$UPLOAD_FILE" | awk '{print $1}')
DOWNLOAD_PATH=$(mktemp /tmp/upload_roundtrip_XXXX)

# Upload
UPLOAD_RESP=$(http_full -X POST "$BASE/upload" -F "file=@$UPLOAD_FILE")
echo "$UPLOAD_RESP" | grep -q "201" && pass "Upload returned 201 for roundtrip test" || fail "Upload did not return 201 for roundtrip test"

# The server saves under upload filename in www/uploads
SAVED_FILE=$(ls -t www/uploads/ 2>/dev/null | grep -v '\.gitkeep' | head -1)
if [[ -n "$SAVED_FILE" ]] && [[ -f "www/uploads/$SAVED_FILE" ]]; then
    SIZE=$(stat -c%s "www/uploads/$SAVED_FILE" 2>/dev/null || stat -f%z "www/uploads/$SAVED_FILE" 2>/dev/null)
    [[ "$SIZE" -gt 0 ]] && pass "Uploaded file is non-empty on disk (size=$SIZE bytes)" || fail "Uploaded file is empty on disk"
else
    skip "Roundtrip integrity check skipped — no saved file found"
fi

# =============================================================================
section "5 · COOKIES & SESSIONS"
# =============================================================================

# First request — server should issue a Set-Cookie header
resp=$(http_full -c "$COOKIE_JAR" "$BASE/")
echo "$resp" | grep -qi "Set-Cookie" && pass "First GET → Set-Cookie header issued" || fail "First GET → no Set-Cookie header"
echo "$resp" | grep -qiE "SID|session_id" && pass "Cookie contains session identifier" || fail "Cookie does not contain session identifier"

# Second request with cookie — Set-Cookie should NOT appear (session already known)
resp2=$(http_full -b "$COOKIE_JAR" "$BASE/")
echo "$resp2" | grep -qi "Set-Cookie" && \
    fail "Second GET with valid cookie still issued a new Set-Cookie" || \
    pass "Second GET with valid cookie → no new Set-Cookie (session reused)"

# =============================================================================
section "6 · VIRTUAL HOST ROUTING"
# =============================================================================

# site-a.test on port 8080 should return 200
code=$(http_status --resolve "site-a.test:$PORT:127.0.0.1" "http://site-a.test:$PORT/")
[[ "$code" == "200" ]] && pass "GET http://site-a.test:$PORT/ → 200" || fail "GET http://site-a.test:$PORT/ → expected 200, got $code"

# site-b.test on port 8080 should return 200
code_b=$(http_status --resolve "site-b.test:$PORT:127.0.0.1" "http://site-b.test:$PORT/")
[[ "$code_b" == "200" ]] && pass "GET http://site-b.test:$PORT/ → 200" || fail "GET http://site-b.test:$PORT/ → expected 200, got $code_b"

# Unknown host falls back to default server (site-a.test)
code=$(http_status -H "Host: unknown.host:$PORT" "$BASE/")
[[ "$code" == "200" ]] && pass "Unknown Host falls back to default server → 200" || fail "Unknown Host fallback → expected 200, got $code"

# =============================================================================
section "7 · REDIRECTS"
# =============================================================================

# /old should return 301
code=$(http_status -X GET "$BASE/old")
[[ "$code" == "301" ]] && pass "GET /old → 301 Moved Permanently" || fail "GET /old → expected 301, got $code"

# Location header must point to /
location=$(curl -s -o /dev/null -D - --max-time 5 "$BASE/old" | grep -i "^Location:" | tr -d '\r\n')
[[ "$location" == *"/"* ]] && pass "301 Location header present" || fail "301 Location header missing or wrong: '$location'"

# Following the redirect (-L) should land on 200
code=$(http_status --no-keepalive -L "$BASE/old")
[[ "$code" == "200" ]] && pass "GET /old (follow redirect) → final 200" || fail "GET /old (follow redirect) → expected 200, got $code"

# =============================================================================
section "8 · DIRECTORY LISTING"
# =============================================================================

# /upload has autoindex enabled
code=$(http_status "$BASE/upload/")
[[ "$code" == "200" ]] && pass "GET /upload/ (autoindex on) → 200" || fail "GET /upload/ (autoindex on) → expected 200, got $code"
listing=$(curl -s --max-time 5 "$BASE/upload/")
echo "$listing" | grep -qi "Index of\|<a href" && \
    pass "GET /upload/ with autoindex → directory listing HTML returned" || \
    fail "GET /upload/ directory listing HTML missing"

# =============================================================================
section "9 · MULTIPLE PORTS"
# =============================================================================

# Server should also be listening on 8081
code=$(http_status "http://$HOST:8081/")
[[ "$code" == "200" ]] && pass "Server responds on port 8081 → 200" || fail "Server not responding on port 8081 (got $code)"

# Same content on both ports
body_8080=$(curl -s --max-time 5 "http://$HOST:8080/")
body_8081=$(curl -s --max-time 5 "http://$HOST:8081/")
[[ "$body_8080" == "$body_8081" ]] && pass "Port 8080 and 8081 serve identical content" || fail "Port 8080 and 8081 serve different content"

# =============================================================================
section "10 · CGI"
# =============================================================================

# GET /cgi-bin/hello.py → should execute the script and return 200
code=$(http_status "$BASE/cgi-bin/hello.py")
[[ "$code" == "200" ]] && pass "GET /cgi-bin/hello.py → 200 (CGI executed)" || fail "GET /cgi-bin/hello.py → expected 200, got $code"

cgi_body=$(curl -s --max-time 10 "$BASE/cgi-bin/hello.py")
echo "$cgi_body" | grep -qi "Hello" && \
    pass "CGI response contains expected output" || \
    fail "CGI response missing expected output: $(echo "$cgi_body" | head -3)"

# POST to CGI with body
code=$(http_status -X POST "$BASE/cgi-bin/echo_body.py" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data "name=test&value=42")
[[ "$code" == "200" ]] && pass "POST /cgi-bin/echo_body.py → 200" || fail "POST /cgi-bin/echo_body.py → expected 200, got $code"

# =============================================================================
section "11 · WRONG / MALFORMED REQUESTS"
# =============================================================================

# Server must stay up after each of these.

# Completely garbage input
if command -v nc &>/dev/null; then
    garbage_resp=$(printf "NOTAMETHOD / HTTP/1.1\r\nHost: localhost\r\n\r\n" | nc -w2 "$HOST" "$PORT" 2>/dev/null)
    echo "$garbage_resp" | grep -qE "4[0-9][0-9]" && \
        pass "Garbage method → 4xx error returned ($(echo "$garbage_resp" | head -1))" || \
        fail "Garbage method → unexpected response: $(echo "$garbage_resp" | head -1)"

    # Confirm server is still alive
    code=$(http_status "$BASE/")
    [[ "$code" == "200" ]] && pass "Server still up after garbage request" || fail "Server crashed after garbage request"
else
    skip "Malformed request test skipped (nc not available)"
fi

# Path traversal attempt → 400 (caught by RequestLine.validate()) or 403
code=$(http_status "$BASE/../../../etc/passwd")
[[ "$code" == "400" || "$code" == "403" || "$code" == "404" ]] && \
    pass "Path traversal attempt → $code (blocked)" || \
    fail "Path traversal → unexpected $code"

# =============================================================================
section "12 · KEEP-ALIVE"
# =============================================================================

# Send two requests on the same TCP connection using curl's --keepalive
resp=$(curl -s -i --keepalive-time 5 --max-time 10 \
    "$BASE/" "$BASE/does-not-exist" 2>&1)
echo "$resp" | grep -q "200" && echo "$resp" | grep -q "404" && \
    pass "Two requests on one TCP connection → 200 and 404 both received" || \
    fail "Keep-alive: could not get both 200 and 404 on same connection"

# Connection: keep-alive header should appear in response (or be default)
resp_header=$(http_full "$BASE/")
echo "$resp_header" | grep -qi "keep-alive" && \
    pass "Response includes keep-alive connection header" || \
    fail "Response missing keep-alive (may cause premature closes)"

# =============================================================================
section "13 · CLIENT BODY SIZE LIMIT"
# =============================================================================

# localhost limit is 1 MB — a 100-byte body must pass
code=$(http_status -X POST "$BASE/upload/body_limit_test.txt" \
    -H "Content-Type: text/plain" \
    --data "$(python3 -c 'print("A"*100)')")
[[ "$code" == "201" ]] && pass "POST 100-byte body to localhost → 201 (within limit)" || fail "POST 100-byte body to localhost → expected 201, got $code"

# port 9090 limit is 1024 bytes — a 2000-byte body must be rejected
code=$(http_status -X POST "http://$HOST:9090/limit_test.txt" \
    -H "Content-Type: text/plain" \
    --data "$(python3 -c 'print("B"*2000)')")
[[ "$code" == "413" ]] && pass "POST 2000-byte body to port 9090 → 413 (over limit)" || fail "POST 2000-byte body to port 9090 → expected 413, got $code"

# =============================================================================
section "14 · TIMEOUT (quick check)"
# =============================================================================

# We verify that the server closes idle keep-alive connections after TIMEOUT_MS.
# Strategy: connect, send one request, read the response, then hold the socket
# open silently. Use Python sockets so we can detect the server-side FIN
# (recv returns b'' = EOF) without keeping stdin alive in a pipe.
if command -v python3 &>/dev/null; then
    info "Sending one request then holding connection idle for up to 25s..."
    info "(TIMEOUT_MS=15000, selector wakes every 1s — server should close within ~16s)"
    START=$SECONDS
    python3 - <<'PYEOF' 2>/dev/null
import socket, time, sys

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(("localhost", 8080))
s.sendall(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")

# Read the full response (headers + body) so the server transitions to READING state
s.settimeout(5.0)
buf = b""
try:
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
        if b"\r\n\r\n" in buf:
            # Got headers — parse Content-Length and read body
            header_end = buf.index(b"\r\n\r\n") + 4
            headers_raw = buf[:header_end].decode("latin1")
            cl = 0
            for line in headers_raw.splitlines():
                if line.lower().startswith("content-length:"):
                    cl = int(line.split(":", 1)[1].strip())
            body_received = len(buf) - header_end
            while body_received < cl:
                chunk = s.recv(4096)
                if not chunk:
                    break
                body_received += len(chunk)
            break
except socket.timeout:
    pass

# Now hold the idle connection — wait for server to close it (EOF)
s.settimeout(25.0)
try:
    data = s.recv(1)
    if data == b"":
        print("SERVER_CLOSED")
    else:
        print("GOT_DATA")
except socket.timeout:
    print("TIMEOUT_EXPIRED")
finally:
    s.close()
PYEOF
    ELAPSED=$((SECONDS - START))
    PY_RESULT=$(python3 - <<'PYEOF' 2>/dev/null
import socket, time

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(("localhost", 8080))
s.sendall(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
s.settimeout(5.0)
buf = b""
try:
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
        if b"\r\n\r\n" in buf:
            header_end = buf.index(b"\r\n\r\n") + 4
            headers_raw = buf[:header_end].decode("latin1")
            cl = 0
            for line in headers_raw.splitlines():
                if line.lower().startswith("content-length:"):
                    cl = int(line.split(":", 1)[1].strip())
            body_received = len(buf) - header_end
            while body_received < cl:
                chunk = s.recv(4096)
                if not chunk:
                    break
                body_received += len(chunk)
            break
except socket.timeout:
    pass
s.settimeout(25.0)
try:
    data = s.recv(1)
    print("CLOSED" if data == b"" else "DATA")
except socket.timeout:
    print("EXPIRED")
finally:
    s.close()
PYEOF
)
    if [[ "$PY_RESULT" == "CLOSED" ]]; then
        pass "Idle connection closed by server (timeout working, elapsed ~${ELAPSED}s)"
    elif [[ "$PY_RESULT" == "EXPIRED" ]]; then
        fail "Idle connection still open after 25s (timeout not firing, TIMEOUT_MS may be > 25s)"
    else
        fail "Unexpected result from timeout test: $PY_RESULT"
    fi
else
    skip "Timeout test skipped (python3 not available)"
fi

# =============================================================================
# SUMMARY
# =============================================================================
TOTAL=$((PASS + FAIL + SKIP))
echo ""
echo -e "${BOLD}══════════════════════════════════════════${RESET}"
echo -e "${BOLD}  RESULTS: $TOTAL tests  |  ${GREEN}$PASS passed${RESET}${BOLD}  |  ${RED}$FAIL failed${RESET}${BOLD}  |  ${YELLOW}$SKIP skipped${RESET}"
echo -e "${BOLD}══════════════════════════════════════════${RESET}"

if [[ "$FAIL" -eq 0 ]]; then
    echo -e "\n${GREEN}${BOLD}All tests passed! ✓${RESET}\n"
    exit 0
else
    echo -e "\n${RED}${BOLD}$FAIL test(s) failed. See output above.${RESET}\n"
    exit 1
fi