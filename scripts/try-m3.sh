#!/usr/bin/env bash
# Hand-test of M3 against a running server (java -cp out localserver.Main config.json).
# Needs: curl, nc, sha256sum, head, dd. Uses the sample config.json: port 8080 hosts site-a.test and
# site-b.test, port 9090 has a 1024-byte body limit.
BASE=${BASE:-127.0.0.1}
say() { printf '\n== %s\n' "$*"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
want() { # want <description> <expected> <actual>
  if [ "$2" = "$3" ]; then printf '  ok   %-58s %s\n' "$1" "$3"; else printf '  FAIL %-58s expected %s, got %s\n' "$1" "$2" "$3"; FAILED=1; fi
}
FAILED=0
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

say "parsing: path normalization, query, host"
OUT=$(curl -s "http://$BASE:8080/a/./b%20c/../d?x=1&y=%20" -H 'Host: Site-A.test:8080')
want "path decoded once and normalized"      "path:       /a/d"       "$(echo "$OUT" | grep '^path:')"
want "query kept raw"                        "query:      x=1&y=%20"  "$(echo "$OUT" | grep '^query:')"
want "host lowercased, port dropped"         "host:       site-a.test" "$(echo "$OUT" | grep '^host:')"

say "virtual hosts on one port (curl --resolve)"
want "site-a.test -> servers[0]" "server:     servers[0] via 127.0.0.1:8080" "$(curl -s --resolve site-a.test:8080:127.0.0.1 http://site-a.test:8080/ | grep '^server:')"
want "site-b.test -> servers[1]" "server:     servers[1] via 127.0.0.1:8080" "$(curl -s --resolve site-b.test:8080:127.0.0.1 http://site-b.test:8080/ | grep '^server:')"
want "unknown host -> default"   "server:     servers[0] via 127.0.0.1:8080" "$(curl -s -H 'Host: nope.test' http://$BASE:8080/ | grep '^server:')"

say "malformed and hostile requests -> 400, server keeps working"
want "garbage request line"         400 "$(printf 'GARBAGE\r\n\r\n' | nc -q1 $BASE 8080 | head -1 | cut -d' ' -f2)"
want "HTTP/1.1 without Host"        400 "$(printf 'GET / HTTP/1.1\r\n\r\n' | nc -q1 $BASE 8080 | head -1 | cut -d' ' -f2)"
want "Content-Length + chunked"     400 "$(printf 'POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n' | nc -q1 $BASE 8080 | head -1 | cut -d' ' -f2)"
want "path traversal"               400 "$(status --path-as-is "http://$BASE:8080/static/../../etc/passwd")"
want "encoded traversal"            400 "$(status --path-as-is "http://$BASE:8080/%2e%2e/%2e%2e/etc/passwd")"
want "server still answers"         200 "$(status http://$BASE:8080/)"

say "methods"
want "GET"                          200 "$(status http://$BASE:8080/)"
want "POST"                         200 "$(status -X POST --data hello http://$BASE:8080/)"
want "DELETE"                       200 "$(status -X DELETE http://$BASE:8080/x)"
want "PUT is not supported"         405 "$(status -X PUT --data x http://$BASE:8080/)"
want "Allow header on 405"          "Allow: GET, POST, DELETE" "$(curl -s -i -X PUT http://$BASE:8080/ | tr -d '\r' | grep -i '^allow:')"

say "bodies arrive intact (sha256 of what was sent == sha256 the server computed)"
head -c 700000 /dev/urandom > "$TMP/blob"
SUM=$(sha256sum "$TMP/blob" | cut -d' ' -f1)
want "700 KB with Content-Length"   "ok" "$(curl -s --data-binary @"$TMP/blob" -H 'Content-Type: application/octet-stream' http://$BASE:8080/up | grep -q "fixed, 700000 bytes, sha256 $SUM" && echo ok || echo MISMATCH)"
want "700 KB chunked"               "ok" "$(curl -s -H 'Transfer-Encoding: chunked' --data-binary @"$TMP/blob" http://$BASE:8080/up | grep -q "chunked, 700000 bytes, sha256 $SUM" && echo ok || echo MISMATCH)"
want "Expect: 100-continue"         "ok" "$(curl -s -H 'Expect: 100-continue' --data-binary @"$TMP/blob" http://$BASE:8080/up | grep -q "$SUM" && echo ok || echo MISMATCH)"

say "client body limit (port 9090 allows 1024 bytes)"
head -c 1024 /dev/zero > "$TMP/ok1024"; head -c 1025 /dev/zero > "$TMP/big1025"; head -c 5000000 /dev/zero > "$TMP/huge"
want "exactly 1024 bytes"           200 "$(status --data-binary @"$TMP/ok1024" http://$BASE:9090/)"
want "1025 bytes"                   413 "$(status --data-binary @"$TMP/big1025" http://$BASE:9090/)"
want "5 MB upload (still gets 413)" 413 "$(status --data-binary @"$TMP/huge" http://$BASE:9090/)"
want "chunked over the limit"       413 "$(status -H 'Transfer-Encoding: chunked' --data-binary @"$TMP/big1025" http://$BASE:9090/)"
want "the plain curl example"       413 "$(status -X POST -H 'Content-Type: plain/text' --data "$(head -c 2000 /dev/zero | tr '\0' 'a')" http://$BASE:9090/)"

say "keep-alive: two requests, one connection"
want "second request reuses it"     "1 0" "$(curl -s -o /dev/null -o /dev/null -w '%{num_connects} ' http://$BASE:8080/1 http://$BASE:8080/2 | xargs)"

echo
if [ "$FAILED" = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
