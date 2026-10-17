package localserver.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import localserver.config.ConfigLoader;
import localserver.config.LoadedConfig;
import localserver.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests: the real Server on real sockets, driven by plain blocking clients. */
class ServerTest {

    private static final String GET = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

    @TempDir
    Path base;

    private Server server;
    private Thread loop;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void quiet() {
        Log.setQuiet(true);
    }

    @BeforeEach
    void layout() throws IOException {
        Files.createDirectories(base.resolve("www"));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        if (server != null) {
            server.stop();
            loop.join(5000);
            assertFalse(loop.isAlive(), "the event loop must end after stop()");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PORT =
            new java.util.concurrent.atomic.AtomicInteger(20000);

    private static int freePort() {
        return NEXT_PORT.incrementAndGet();
    }

    private Server start(String settingsJson, int... ports) throws Exception {
        return startWith(settingsJson, "", ports);
    }

    /** serverExtra is spliced into the server block and must end with a comma, e.g. {@code "client_max_body_size": 1024,}. */
    private Server startWith(String settingsJson, String serverExtra, int... ports) throws Exception {
        String portList = Arrays.stream(ports).mapToObj(String::valueOf).collect(Collectors.joining(","));
        String json = """
                {"settings": %s,
                 "servers": [{"ports": [%s], %s "routes": [{"path": "/", "root": "www"}]}]}
                """.formatted(settingsJson, portList, serverExtra);
        return startJson(json);
    }

    private Server startJson(String json) throws Exception {
        LoadedConfig config = ConfigLoader.parse(json, base);
        server = Server.open(config);
        loop = new Thread(server::run, "server-loop");
        loop.start();
        return server;
    }

    private Server start(String settingsJson) throws Exception {
        return start(settingsJson, freePort());
    }

    private int port() {
        return server.listeners().get(0).port();
    }

    private record Response(int status, Map<String, String> headers, String body) { }

    private final class Client {
        final Socket socket;
        final BufferedInputStream in;
        final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            sockets.add(socket);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void send(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        void sendBytes(byte[] data) throws IOException {
            out.write(data);
            out.flush();
        }

        Response read() throws IOException {
            return readResponse(in);
        }

        Response get() throws IOException {
            send(GET);
            return read();
        }

        /** True if the server closes the connection within waitMs. */
        boolean closedByServer(int waitMs) throws IOException {
            socket.setSoTimeout(waitMs);
            try {
                return in.read() == -1;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                return true; // reset counts as closed
            }
        }
    }

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new EOFException("connection closed before a response");
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("short body: " + body.length + " of " + length);
        }
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(10);
        }
    }

    // ---- basic behaviour -----------------------------------------------------------------------------

    @Test
    void answersARequestWithAWellFormedResponse() throws Exception {
        start("{}");
        Response r = new Client(port()).get();
        assertEquals(200, r.status());
        assertEquals(String.valueOf(r.body().getBytes(StandardCharsets.UTF_8).length), r.headers().get("content-length"));
        assertEquals("text/plain; charset=utf-8", r.headers().get("content-type"));
        assertTrue(r.headers().containsKey("date"));
        assertTrue(r.headers().get("date").endsWith("GMT"));
        assertEquals("keep-alive", r.headers().get("connection"));
        assertTrue(r.body().contains("method:     GET"), r.body());
        await(() -> server.stats().requests() == 1, 2000, "request counter");
    }

    @Test
    void listensOnEveryConfiguredPort() throws Exception {
        int a = freePort();
        int b = freePort();
        start("{}", a, b);
        assertTrue(new Client(a).get().body().contains(":" + a));
        assertTrue(new Client(b).get().body().contains(":" + b));
        assertEquals(2, server.listeners().size());
    }

    @Test
    void keepAliveServesSeveralRequestsOnOneConnection() throws Exception {
        start("{}");
        Client c = new Client(port());
        for (int i = 0; i < 5; i++) {
            assertEquals(200, c.get().status(), "request " + i);
        }
        assertEquals(1, server.stats().accepted());
    }

    @Test
    void connectionCloseIsHonored() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        Response r = c.read();
        assertEquals(200, r.status());
        assertEquals("close", r.headers().get("connection"));
        assertTrue(c.closedByServer(2000));
        await(() -> server.stats().open() == 0, 2000, "connection released");
    }

    @Test
    void http10ClosesByDefault() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.0\r\n\r\n");
        assertEquals(200, c.read().status());
        assertTrue(c.closedByServer(2000));
    }

    // ---- partial and batched input ----------------------------------------------------------------------

    @Test
    void aRequestArrivingByteByByteGetsOneResponse() throws Exception {
        start("{}");
        Client c = new Client(port());
        for (char ch : GET.toCharArray()) {
            c.send(String.valueOf(ch));
            Thread.sleep(2);
        }
        assertEquals(200, c.read().status());
        assertEquals(200, c.get().status(), "the connection is still in a clean state");
    }

    @Test
    void pipelinedRequestsAreAnsweredInOrder() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("GET /1 HTTP/1.1\r\nHost: x\r\n\r\nGET /2 HTTP/1.1\r\nHost: x\r\n\r\nGET /3 HTTP/1.1\r\nHost: x\r\n\r\n");
        for (int i = 0; i < 3; i++) {
            assertEquals(200, c.read().status(), "pipelined response " + i);
        }
        await(() -> server.stats().requests() == 3, 2000, "three requests counted");
    }

    @Test
    void anOversizedHeadGets400AndTheConnectionIsClosed() throws Exception {
        start("{\"max_header_bytes\": 256}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.1\r\nX-Big: " + "a".repeat(1000) + "\r\n\r\n");
        Response r = c.read();
        assertEquals(400, r.status());
        assertEquals("close", r.headers().get("connection"));
        assertTrue(c.closedByServer(2000));
    }

    @Test
    void binaryGarbageGets400AndDoesNotBreakTheServer() throws Exception {
        start("{}");
        Client bad = new Client(port());
        bad.sendBytes(new byte[] {0, 1, 2, (byte) 255, (byte) 254, '\r', '\n', '\r', '\n'});
        assertEquals(400, bad.read().status());
        assertTrue(bad.closedByServer(2000));
        assertEquals(200, new Client(port()).get().status(), "other clients are unaffected");
    }

    // ---- timeouts ------------------------------------------------------------------------------------------

    @Test
    void aConnectionThatSendsNothingIsClosedAfterTheKeepAliveTimeout() throws Exception {
        start("{\"keep_alive_timeout_ms\": 300, \"idle_timeout_ms\": 5000, \"request_timeout_ms\": 5000}");
        Client c = new Client(port());
        long t0 = System.nanoTime();
        assertTrue(c.closedByServer(4000), "server should close an idle new connection");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 250 && ms < 2500, "closed after " + ms + " ms");
        await(() -> server.stats().open() == 0, 2000, "connection released");
        assertTrue(server.stats().timedOut() >= 1);
    }

    @Test
    void anIdleKeepAliveConnectionIsClosedAfterItsResponse() throws Exception {
        start("{\"keep_alive_timeout_ms\": 300, \"idle_timeout_ms\": 5000, \"request_timeout_ms\": 5000}");
        Client c = new Client(port());
        assertEquals(200, c.get().status());
        assertTrue(c.closedByServer(4000));
    }

    @Test
    void aRequestThatStallsHalfwayIsClosedByTheInactivityTimeout() throws Exception {
        start("{\"keep_alive_timeout_ms\": 5000, \"idle_timeout_ms\": 300, \"request_timeout_ms\": 5000}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.1\r\nHo");
        assertTrue(c.closedByServer(4000));
        await(() -> server.stats().open() == 0, 2000, "connection released");
    }

    @Test
    void aSlowTricklingClientIsCutOffByTheRequestDeadline() throws Exception {
        // Bytes keep arriving, so the inactivity clock never fires; only the request deadline can.
        start("{\"keep_alive_timeout_ms\": 5000, \"idle_timeout_ms\": 5000, \"request_timeout_ms\": 500}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.1\r\nHost: x\r\n");
        long t0 = System.nanoTime();
        boolean closed = false;
        while (!closed && (System.nanoTime() - t0) < 4_000_000_000L) {
            try {
                c.send("X");
            } catch (IOException e) {
                closed = true;
                break;
            }
            closed = c.closedByServer(60);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(closed, "the trickling client must be disconnected");
        assertTrue(ms < 3000, "cut off after " + ms + " ms");
    }

    // ---- clients that misbehave --------------------------------------------------------------------------------

    @Test
    void aClientThatDisconnectsMidRequestIsCleanedUp() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("GET / HTTP/1.1\r\nHost: x\r\n");
        await(() -> server.stats().open() == 1, 2000, "connection registered");
        c.socket.close();
        await(() -> server.stats().open() == 0, 2000, "connection released after the client left");
        assertEquals(200, new Client(port()).get().status());
    }

    @Test
    void manyClientsConnectingAndLeavingLeaveNothingBehind() throws Exception {
        start("{}");
        for (int i = 0; i < 100; i++) {
            Client c = new Client(port());
            if (i % 2 == 0) {
                c.get();
            }
            c.socket.close();
        }
        await(() -> server.stats().open() == 0, 3000, "all connections released");
        assertEquals(100, server.stats().accepted());
    }

    // ---- limits --------------------------------------------------------------------------------------------------

    @Test
    void connectionsBeyondTheCapAreRefusedAndSlotsAreReusable() throws Exception {
        start("{\"max_connections\": 2}");
        Client c1 = new Client(port());
        Client c2 = new Client(port());
        assertEquals(200, c1.get().status());
        assertEquals(200, c2.get().status());

        Client c3 = new Client(port());
        assertTrue(c3.closedByServer(3000), "third connection must be shed");
        assertTrue(server.stats().refused() >= 1);
        assertEquals(200, c1.get().status(), "existing clients are unaffected");

        c1.socket.close();
        await(() -> server.stats().open() == 1, 2000, "slot freed");
        assertEquals(200, new Client(port()).get().status());
    }

    // ---- start-up and shutdown ----------------------------------------------------------------------------------------

    @Test
    void aPortThatCannotBeBoundOnlySkipsThatListener() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int busy = occupied.getLocalPort();
            int free = freePort();
            start("{}", busy, free);
            assertEquals(1, server.listeners().size());
            assertEquals(1, server.bindFailures().size());
            assertTrue(server.bindFailures().get(0).contains(String.valueOf(busy)), server.bindFailures().toString());
            assertEquals(200, new Client(free).get().status());
        }
    }

    @Test
    void ifNoListenerCanBeBoundStartingFails() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            IOException e = assertThrows(IOException.class, () -> start("{}", occupied.getLocalPort()));
            assertTrue(e.getMessage().contains("no listener could be opened"), e.getMessage());
            server = null; // nothing was started
        }
    }

    @Test
    void stopEndsTheLoopAndReleasesThePort() throws Exception {
        start("{}");
        int port = port();
        assertEquals(200, new Client(port).get().status());
        server.stop();
        loop.join(5000);
        assertFalse(loop.isAlive());
        assertThrows(IOException.class, () -> new Socket(InetAddress.getByName("127.0.0.1"), port));
    }

    // ---- load -----------------------------------------------------------------------------------------------------------

    @Test
    void hundredsOfConcurrentClientsAllGetAnswers() throws Exception {
        start("{}");
        int port = port();
        int clients = 300;
        int requestsEach = 3;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    int ok = 0;
                    try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                        s.setSoTimeout(5000);
                        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        for (int r = 0; r < requestsEach; r++) {
                            out.write(GET.getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                            if (readResponse(in).status() == 200) {
                                ok++;
                            }
                        }
                    }
                    return ok;
                }));
            }
            int total = 0;
            for (Future<Integer> f : results) {
                total += f.get(30, TimeUnit.SECONDS);
            }
            assertEquals(clients * requestsEach, total, "every request must be answered with 200");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        await(() -> server.stats().open() == 0, 5000, "all connections released after the load");
        assertEquals(clients * requestsEach, server.stats().requests());
    }

    // ======================================================================================================
    // M3: parsing, bodies, limits
    // ======================================================================================================

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] data = new byte[n];
        new Random(seed).nextBytes(data);
        return data;
    }

    private boolean tempDirEmpty() {
        try (var files = Files.list(server.tempDir())) {
            return files.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static String post(String path, int length, String... extraHeaders) {
        return "POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + length + "\r\n"
                + String.join("", Arrays.stream(extraHeaders).map(h -> h + "\r\n").toArray(String[]::new)) + "\r\n";
    }

    @Test
    void theEchoShowsExactlyWhatWasParsed() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("GET /a/./b%20c/../d?x=1&y=%20 HTTP/1.1\r\nHost: Site.Test:8080\r\nX-Tag:  padded  \r\n\r\n");
        Response r = c.read();
        assertEquals(200, r.status());
        assertTrue(r.body().contains("path:       /a/d"), r.body());
        assertTrue(r.body().contains("query:      x=1&y=%20"), r.body());
        assertTrue(r.body().contains("host:       site.test"), r.body());
        assertTrue(r.body().contains("X-Tag: padded"), r.body());
        assertTrue(r.body().contains("body:       none"), r.body());
    }

    @Test
    void aSmallBodyWithContentLengthArrives() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send(post("/s", 5) + "hello");
        Response r = c.read();
        assertEquals(200, r.status());
        assertTrue(r.body().contains("fixed, 5 bytes, sha256 " + sha256("hello".getBytes(StandardCharsets.UTF_8))), r.body());
    }

    @Test
    void aLargeBinaryUploadArrivesByteForByteThroughATempFile() throws Exception {
        start("{}");
        byte[] data = randomBytes(300_000, 42);
        Client c = new Client(port());
        c.send(post("/up", data.length));
        c.sendBytes(data);
        Response r = c.read();
        assertEquals(200, r.status());
        assertTrue(r.body().contains("fixed, 300000 bytes, sha256 " + sha256(data)), r.body());
        await(this::tempDirEmpty, 3000, "the temp file is removed once the request is finished");
        assertEquals(200, c.get().status(), "the connection stays usable");
    }

    @Test
    void aChunkedBodyIsDecodedAndArrivesIntact() throws Exception {
        start("{}");
        byte[] data = randomBytes(70_000, 7);
        Client c = new Client(port());
        c.send("POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n");
        int off = 0;
        int i = 0;
        while (off < data.length) {
            int n = Math.min(8192, data.length - off);
            c.send(Integer.toHexString(n) + (i == 1 ? ";ext=1" : "") + "\r\n");
            c.out.write(data, off, n);
            c.send("\r\n");
            off += n;
            if (++i % 3 == 0) {
                Thread.sleep(5);
            }
        }
        c.send("0\r\nX-Trailer: 1\r\n\r\n");
        Response r = c.read();
        assertEquals(200, r.status());
        assertTrue(r.body().contains("chunked, 70000 bytes, sha256 " + sha256(data)), r.body());
        await(this::tempDirEmpty, 3000, "temp file removed");
    }

    @Test
    void aChunkedBodyDeliveredInTinyWritesStillWorks() throws Exception {
        start("{}");
        Client c = new Client(port());
        String wire = "POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        for (char ch : wire.toCharArray()) {
            c.send(String.valueOf(ch));
            Thread.sleep(1);
        }
        Response r = c.read();
        assertTrue(r.body().contains("chunked, 11 bytes, sha256 " + sha256("hello world".getBytes(StandardCharsets.UTF_8))), r.body());
    }

    @Test
    void aPostAndAGetPipelinedTogetherAreBothAnswered() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send(post("/1", 5) + "hello" + "GET /2 HTTP/1.1\r\nHost: x\r\n\r\n");
        assertTrue(c.read().body().contains("path:       /1"));
        assertTrue(c.read().body().contains("path:       /2"));
    }

    @Test
    void requestsWithBodiesCanBeRepeatedOnOneConnection() throws Exception {
        start("{}");
        Client c = new Client(port());
        for (int i = 0; i < 5; i++) {
            byte[] data = randomBytes(1000 + i * 100_000, i);
            c.send(post("/r", data.length));
            c.sendBytes(data);
            assertTrue(c.read().body().contains(sha256(data)), "round " + i);
        }
        await(this::tempDirEmpty, 3000, "temp files removed");
    }

    // ---- Expect: 100-continue -----------------------------------------------------------------------------------

    @Test
    void expectContinueGetsAnInterimResponseBeforeTheBodyIsSent() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send(post("/e", 5, "Expect: 100-continue"));
        assertEquals(100, c.read().status());
        c.send("hello");
        Response r = c.read();
        assertEquals(200, r.status());
        assertTrue(r.body().contains("fixed, 5 bytes"));
    }

    @Test
    void aBodySentEagerlyDespiteExpectIsStillHandled() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send(post("/e", 5, "Expect: 100-continue") + "hello");
        assertEquals(100, c.read().status());
        assertEquals(200, c.read().status());
    }

    @Test
    void anOverlargeRequestWithExpectContinueIsRefusedWithoutAContinue() throws Exception {
        startWith("{}", "\"client_max_body_size\": 1024,", freePort());
        Client c = new Client(port());
        c.send(post("/e", 5000, "Expect: 100-continue"));
        assertEquals(413, c.read().status(), "the client learns it before sending the body");
    }

    // ---- limits (413) --------------------------------------------------------------------------------------------------

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedImmediately() throws Exception {
        startWith("{}", "\"client_max_body_size\": 1024,", freePort());
        Client c = new Client(port());
        c.send(post("/big", 100_000));          // no body is ever sent
        Response r = c.read();
        assertEquals(413, r.status());
        assertEquals("close", r.headers().get("connection"));
        assertTrue(c.closedByServer(2000));
        c.socket.close();
        await(() -> server.stats().open() == 0, 4000, "connection released");
        assertTrue(tempDirEmpty(), "a refused request never creates a temp file");
    }

    @Test
    void aClientStillSendingItsBodyCanStillReadThe413() throws Exception {
        // A slow upload that is refused up front. The client is still writing when the server has
        // already answered, which is exactly the case where closing a socket with unread incoming data
        // makes TCP send a reset: the client's next write fails and the 413 is lost. The lingering close
        // (answer, shut down our side, keep reading and discarding) is what lets the upload finish.
        startWith("{}", "\"client_max_body_size\": 1024,", freePort());
        Client c = new Client(port());
        byte[] data = randomBytes(200_000, 5);
        c.send(post("/big", data.length));
        for (int off = 0; off < data.length; off += 10_000) {
            c.out.write(data, off, 10_000);
            c.out.flush();
            Thread.sleep(40);                                  // a slow uplink: about 1 second in total
        }
        Response r = c.read();
        assertEquals(413, r.status());
        assertTrue(c.closedByServer(3000));
        c.socket.close();
        await(() -> server.stats().open() == 0, 4000, "connection released");
    }

    @Test
    void aChunkedBodyOverTheLimitGets413() throws Exception {
        startWith("{}", "\"client_max_body_size\": 1024,", freePort());
        Client c = new Client(port());
        c.send("POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n");
        c.send("7D0\r\n");                                   // a 2000 byte chunk
        c.sendBytes(randomBytes(2000, 9));
        c.send("\r\n0\r\n\r\n");
        assertEquals(413, c.read().status());
        await(() -> tempDirEmpty(), 3000, "temp file removed after the refusal");
    }

    @Test
    void theLimitBoundaryIsExact() throws Exception {
        startWith("{}", "\"client_max_body_size\": 1024,", freePort());
        Client ok = new Client(port());
        ok.send(post("/x", 1024));
        ok.sendBytes(new byte[1024]);
        assertEquals(200, ok.read().status());

        Client tooBig = new Client(port());
        tooBig.send(post("/x", 1025));
        assertEquals(413, tooBig.read().status());
    }

    // ---- virtual hosts, methods, malformed input ---------------------------------------------------------------------------

    @Test
    void theHostHeaderSelectsTheVirtualHost() throws Exception {
        int port = freePort();
        startJson("""
                {"settings": {}, "servers": [
                  {"ports": [%d], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www"}]},
                  {"ports": [%d], "server_names": ["b.test"], "routes": [{"path": "/", "root": "www"}]}
                ]}
                """.formatted(port, port));
        Client c = new Client(port);
        c.send("GET / HTTP/1.1\r\nHost: b.test:" + port + "\r\n\r\n");
        assertTrue(c.read().body().contains("servers[1]"));
        c.send("GET / HTTP/1.1\r\nHost: A.TEST\r\n\r\n");
        assertTrue(c.read().body().contains("servers[0]"));
        c.send("GET / HTTP/1.1\r\nHost: unknown.test\r\n\r\n");
        assertTrue(c.read().body().contains("servers[0]"), "unknown host falls back to the default server");
        c.send("GET http://b.test/ HTTP/1.1\r\nHost: a.test\r\n\r\n");
        assertTrue(c.read().body().contains("servers[1]"), "an absolute-form target wins over Host");
    }

    @Test
    void eachVirtualHostAppliesItsOwnBodyLimit() throws Exception {
        int port = freePort();
        startJson("""
                {"settings": {}, "servers": [
                  {"ports": [%d], "server_names": ["small.test"], "client_max_body_size": 10, "routes": [{"path": "/", "root": "www"}]},
                  {"ports": [%d], "server_names": ["large.test"], "client_max_body_size": 100000, "routes": [{"path": "/", "root": "www"}]}
                ]}
                """.formatted(port, port));
        Client small = new Client(port);
        small.send("POST / HTTP/1.1\r\nHost: small.test\r\nContent-Length: 50\r\n\r\n");
        assertEquals(413, small.read().status());
        Client large = new Client(port);
        large.send("POST / HTTP/1.1\r\nHost: large.test\r\nContent-Length: 50\r\n\r\n" + "x".repeat(50));
        assertEquals(200, large.read().status());
    }

    @Test
    void anUnsupportedMethodGets405WithAllowAndTheConnectionSurvives() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send("PUT /x HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc");
        Response r = c.read();
        assertEquals(405, r.status());
        assertEquals("GET, POST, DELETE", r.headers().get("allow"));
        assertEquals(200, c.get().status(), "a well-formed request with a refused method keeps the connection");
    }

    @Test
    void malformedRequestsGet400AndTheConnectionIsClosed() throws Exception {
        start("{}");
        List<String> bad = List.of(
                "GARBAGE\r\n\r\n",
                "GET / HTTP/1.1\r\n\r\n",
                "GET / HTTP/9.9\r\nHost: x\r\n\r\n",
                "GET /../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
                "GET / HTTP/1.1\r\nHost x\r\n\r\n");
        for (String raw : bad) {
            Client c = new Client(port());
            c.send(raw);
            Response r = c.read();
            assertEquals(400, r.status(), raw.replace("\r\n", "\\r\\n"));
            assertEquals("close", r.headers().get("connection"));
            assertTrue(c.closedByServer(3000), raw.replace("\r\n", "\\r\\n"));
        }
        assertEquals(200, new Client(port()).get().status(), "the server is unaffected");
    }

    // ---- bodies and failure: nothing may leak ---------------------------------------------------------------------------------

    @Test
    void aBodyThatStallsIsTimedOutAndItsTempFileRemoved() throws Exception {
        start("{\"idle_timeout_ms\": 300, \"keep_alive_timeout_ms\": 5000, \"request_timeout_ms\": 5000}");
        Client c = new Client(port());
        c.send(post("/stall", 200_000));
        c.sendBytes(new byte[1000]);                          // then silence
        assertTrue(c.closedByServer(4000));
        await(this::tempDirEmpty, 3000, "temp file removed after the timeout");
        await(() -> server.stats().open() == 0, 3000, "connection released");
    }

    @Test
    void aClientVanishingMidBodyLeavesNoTempFile() throws Exception {
        start("{}");
        Client c = new Client(port());
        c.send(post("/vanish", 200_000));
        c.sendBytes(new byte[5000]);
        await(() -> !tempDirEmpty(), 3000, "the temp file exists while the body is arriving");
        c.socket.close();
        await(this::tempDirEmpty, 3000, "temp file removed after the client left");
        await(() -> server.stats().open() == 0, 3000, "connection released");
    }

    @Test
    void manyParallelUploadsAreAllIntactAndLeaveNothingBehind() throws Exception {
        start("{}");
        int port = port();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 5; i++) {
                        byte[] data = randomBytes(100_000 + thread * 37_000 + i * 11_000, thread * 100L + i);
                        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                            s.setSoTimeout(10_000);
                            OutputStream out = s.getOutputStream();
                            out.write(post("/p", data.length).getBytes(StandardCharsets.ISO_8859_1));
                            out.write(data);
                            out.flush();
                            Response r = readResponse(new BufferedInputStream(s.getInputStream()));
                            if (r.status() != 200 || !r.body().contains("sha256 " + sha256(data))) {
                                return false;
                            }
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get(60, TimeUnit.SECONDS), "an upload was corrupted or refused");
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        await(this::tempDirEmpty, 5000, "all temp files removed");
        await(() -> server.stats().open() == 0, 5000, "all connections released");
    }

    @Test
    void stoppingTheServerRemovesItsTempDirectory() throws Exception {
        start("{}");
        Path dir = server.tempDir();
        assertTrue(Files.isDirectory(dir));
        server.stop();
        loop.join(5000);
        assertFalse(Files.exists(dir));
    }
}
