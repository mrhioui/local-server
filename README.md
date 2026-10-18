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
    curl -i -H "Host: site-b.test:8080" http://127.0.0.1:8080/
    curl -i http://127.0.0.1:8080/upload/          # directory autoindex listing
    curl -i http://127.0.0.1:8080/cgi-bin/hello.py # asynchronous CGI execution
    curl -i http://127.0.0.1:8080/metrics         # live server metrics
    siege -b -c25 -t10S http://127.0.0.1:8080/    # availability target: 99.5%


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

## HTTP/1.1 Protocol & Request Handling Rules

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

## Tests and Verification

### 1. Automated JUnit Test Suite (362 tests)

Run all unit, integration, protocol, and leak tests:

    mvn test

### 2. Postman & Newman Audit Test Suite (48 requests, 82 assertions)

The primary integration and audit verification suite is the self-contained Postman collection ([`postman_collection.json`](postman_collection.json)). It covers all requirements: routing, virtual hosts, ports (8080, 8081, 9090), static files, directory autoindexing, file uploads, DELETE, cookies/sessions, asynchronous CGI, body limits, custom error pages, and security/traversal guards.

**Run via CLI (Newman):**

    newman run postman_collection.json

**Run via Postman Desktop App (GUI):**

1. Start LocalServer:
   ```bash
   java -jar target/localserver-0.1.0.jar config.json
   ```
2. In the Postman App, click **Import** and choose [`postman_collection.json`](postman_collection.json).
3. Select **LocalServer Full Project & Audit Test Suite** from the left sidebar and click **Run collection**.
   - No external environment file is required (collection variables `baseUrl`, `port8081Url`, and `baseUrlLimit` are pre-configured).
   - Session cookies and test file cleanups are automated by the collection scripts.

### 3. Load & Stress Benchmarking (Siege)

With LocalServer running on `config.json`:

    siege -b -c25 -t15S http://127.0.0.1:8080/

Requirement: Availability $\ge$ 99.5% with 0 socket/connection failures.

## Implemented Architecture & Milestones

- [x] **M0: Project Skeleton & Build**: Java NIO, zero external dependencies, reproducible maven build.
- [x] **M1: Configuration & Virtual Hosts**: JSON parser, route validation, multi-port listeners, host selection.
- [x] **M2: Non-blocking Event Loop**: Single Selector, single thread, dual-clock timeouts (idle/request), connection caps, idempotent close.
- [x] **M3: Strict HTTP/1.1 Parser**: Content-Length vs chunked framing, hostile input sanitization, Expect: 100-continue, lingering close on 400/413.
- [x] **M4: Routing & Static Files**: Longest-prefix match, MIME detection, autoindex directory listings, HTTP 301/302 redirects, custom error pages (400, 403, 404, 405, 413, 500) cached in memory at startup.
- [x] **M5: File Uploads & DELETE**: Raw POST bodies, multipart/form-data parsing, file upload streaming, safe DELETE handling.
- [x] **M6: Sessions & Cookies**: Cryptographic SID session generation, cookie parser/formatter, HttpOnly, SameSite=Lax, TTL expiry.
- [x] **M7: Multi-CGI Execution**: Asynchronous CGI subprocess execution (Python and extensions), non-blocking event-loop polling via temporary file piping.
- [x] **M8: Hardening, Benchmarking & Metrics**: Live metrics dashboard at `/metrics` and `/admin/metrics`, Siege 99.5%+ availability, clean descriptor/temp-file leak checks.

