# LocalServer

A single-threaded, non-blocking HTTP/1.1 server in Java (`java.nio`, no frameworks).
Built milestone by milestone. **Current state: Complete (M0 through M8: configuration, non-blocking event loop, strict HTTP parser, routing, file uploads, DELETE, cookies/sessions, multi-CGI handlers, custom error pages, and metrics dashboard).**

## Build and run

With Maven (zero runtime dependencies, JUnit is test-scope only):

    mvn package
    java -jar target/localserver-0.1.0.jar config.json

Without Maven:

    javac --release 17 -d out $(find src/main -name '*.java')
    java -cp out localserver.Main config.json

`Main` loads and validates the config, opens every listener and runs the event loop until Ctrl-C.
Set `LOCALSERVER_DEBUG=1` for per-connection debug logging.

Try it:

    curl -i http://127.0.0.1:8080/
    siege -b -c25 -t10S http://127.0.0.1:8080/    # availability target: 99.5%
    scripts/try-m3.sh                             # 27 audit-style checks against a running server

Until the router arrives (M4), every valid request gets a `text/plain` description of what was parsed
(method, decoded path, query, virtual host, body size and sha256), which makes parsing and uploads easy
to verify by hand.

## Settings (optional top-level `"settings"`)

Global limits. Invalid values refuse to start (they are not per-server).

| Key | Meaning | Default | Range |
|---|---|---|---|
| `idle_timeout_ms` | a request in progress may not stall (no bytes moving) longer | 15000 | 50-3600000 |
| `keep_alive_timeout_ms` | idle time allowed between requests / on a silent new connection | 5000 | 50-3600000 |
| `request_timeout_ms` | hard deadline per request (stops slow trickling) | 30000 | 50-3600000 |
| `max_header_bytes` | largest request head | 16384 | 256-1048576 |
| `max_connections` | simultaneous clients; extra ones are closed immediately | 512 | 1-100000 |

## Configuration (config.json)

Relative paths resolve against the directory containing the config file.
Unknown keys are errors (a typo must not be silently ignored).

| Server key | Meaning | Default |
|---|---|---|
| `host` | bind address (`localhost` is normalized to `127.0.0.1`) | `127.0.0.1` |
| `ports` | non-empty array of ports, no duplicates | required |
| `server_names` | names matched against the `Host` header (case-insensitive) | none |
| `default` | answer when no name matches / no `Host` header | first block on that listener |
| `client_max_body_size` | body limit in bytes | 1048576 |
| `error_pages` | `{"404": "path.html"}`, files must exist | built-in pages |
| `routes` | non-empty array | required |

| Route key | Meaning | Default |
|---|---|---|
| `path` | URL prefix, whole-segment match, longest wins | required |
| `methods` | subset of `GET`, `POST`, `DELETE` | `["GET"]` |
| `root` | directory served (required unless redirect) | |
| `index` | default file for a directory | `index.html` |
| `autoindex` | list directories without an index file | `false` |
| `redirect` | `{"code": 301, "to": "/x"}`; codes 301/302/303/307/308 | none |
| `cgi` | `{".py": "/usr/bin/python3"}`; `""` runs the file directly | none |

### Failure policy

* File-level problems (unreadable, invalid JSON, no usable server block): the server refuses to start.
* A problem inside one server block, or a conflict between blocks: only that block is skipped (the
  later one, for conflicts), the reason is printed, and the other blocks keep working.
* Servers may share a (host, port) when their `server_names` differ (virtual hosting). Rejected:
  same name twice on a listener, two unnamed servers on a listener, two `default`s on a listener,
  a wildcard address (`0.0.0.0`) overlapping another address on the same port.

## What the parser accepts and refuses (M3)

* Request line `METHOD SP target SP HTTP/1.1|1.0`; origin-form and absolute-form targets. Anything else is 400.
* Path: percent-decoded exactly once, must be valid UTF-8, no NUL / control characters / backslash;
  `.` and `..` segments are resolved and a path that climbs above the root is 400.
* Headers: strict (no obsolete folding, no space before the colon, no control characters, at most 100).
  HTTP/1.1 needs exactly one `Host`.
* Body framing: `Content-Length` (digits only, duplicates must agree) or `Transfer-Encoding: chunked`;
  both together, or any other transfer coding, is 400. Chunked bodies arrive de-chunked.
* `client_max_body_size` is enforced per virtual host: a declared length over the limit is 413 before a body
  byte is read, a chunked body is 413 as soon as the total would exceed it.
* `Expect: 100-continue` is honored. Small bodies stay in memory, larger ones are spooled to a temp
  file that is deleted when the request ends (or the client disappears).
* Any refusal (400, 413, ...) is answered with `Connection: close` and a lingering close: the server shuts down
  its sending side and briefly reads and discards what the client is still sending, so the client can read the
  error instead of getting a TCP reset.
* A well-formed request with an unsupported method gets 405 with an `Allow` header and the connection stays open.

## Tests

    mvn test

## Milestones

- [x] M0 skeleton, build
- [x] M1 config: JSON parser, validation, listeners, virtual host selection
- [x] M2 event loop: one Selector, accept/read/write, two-clock timeouts, connection cap, idempotent close
- [x] M3 HTTP parser: strict head, fixed + chunked bodies, per-host limits, Expect, lingering close
- [x] M4 router, static files, error pages, redirects, directory listing
- [x] M5 uploads and DELETE
- [x] M6 cookies and sessions
- [x] M7 CGI
- [x] M8 hardening, siege, leak checks, bonus
