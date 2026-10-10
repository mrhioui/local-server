package localserver.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import localserver.http.HttpParser.Status;
import localserver.http.Request.BodyMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpParserTest {

    private static final long NO_LIMIT = Long.MAX_VALUE;

    private final AtomicInteger sinksCreated = new AtomicInteger();

    // ---- helpers ------------------------------------------------------------------------------------

    private HttpParser parser(long limit) {
        return new HttpParser(16 * 1024, req -> HeadDecision.accept(limit, () -> {
            sinksCreated.incrementAndGet();
            return new MemoryBodySink();
        }));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String head(String requestLine, String... headers) {
        StringBuilder sb = new StringBuilder(requestLine).append("\r\n");
        for (String h : headers) {
            sb.append(h).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    /** Feeds the data in the given pieces; returns the final status. */
    private static Status feedPieces(HttpParser p, byte[]... pieces) {
        Status s = Status.NEED_MORE;
        for (byte[] piece : pieces) {
            ByteBuffer buf = ByteBuffer.wrap(piece);
            while (true) {
                s = p.feed(buf);
                if (s != Status.SEND_CONTINUE) {
                    break;
                }
            }
            if (s == Status.COMPLETE || s == Status.ERROR) {
                return s;
            }
        }
        return s;
    }

    private Request parseOk(String raw) {
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(p, bytes(raw)), raw);
        return p.request();
    }

    private ParseError parseError(String raw) {
        HttpParser p = parser(NO_LIMIT);
        Status s = feedPieces(p, bytes(raw));
        assertEquals(Status.ERROR, s, "expected an error for: " + raw.replace("\r", "\\r").replace("\n", "\\n"));
        return p.error();
    }

    private static byte[] readAll(BodySink sink) throws IOException {
        try (InputStream in = sink.openRead()) {
            return in.readAllBytes();
        }
    }

    private static String summary(Request r) throws IOException {
        String body = r.body() == null ? "-" : Base64.getEncoder().encodeToString(readAll(r.body()));
        return String.join("|", r.method(), r.target(), r.path(), r.query(), r.version(), r.host(),
                String.valueOf(r.keepAlive()), r.bodyMode().toString(), String.valueOf(r.headers().count()), body);
    }

    private static byte[] chunked(byte[] data, int chunkSize) {
        StringBuilder out = new StringBuilder();
        List<byte[]> parts = new ArrayList<>();
        int total = 0;
        for (int off = 0; off < data.length; off += chunkSize) {
            int n = Math.min(chunkSize, data.length - off);
            byte[] hexLine = bytes(Integer.toHexString(n) + "\r\n");
            byte[] chunk = new byte[hexLine.length + n + 2];
            System.arraycopy(hexLine, 0, chunk, 0, hexLine.length);
            System.arraycopy(data, off, chunk, hexLine.length, n);
            chunk[chunk.length - 2] = '\r';
            chunk[chunk.length - 1] = '\n';
            parts.add(chunk);
            total += chunk.length;
        }
        byte[] last = bytes("0\r\n\r\n");
        parts.add(last);
        total += last.length;
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, pos, part.length);
            pos += part.length;
        }
        return all;
    }

    private static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) {
            n += a.length;
        }
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    // ---- the happy path ------------------------------------------------------------------------------

    @Test
    void parsesASimpleGet() throws Exception {
        Request r = parseOk(head("GET /index.html?x=1&y=%20 HTTP/1.1", "Host: Example.TEST:8080", "Accept:  */*  "));
        assertEquals("GET", r.method());
        assertEquals("/index.html?x=1&y=%20", r.target());
        assertEquals("/index.html", r.path());
        assertEquals("x=1&y=%20", r.query(), "the query stays raw");
        assertEquals("HTTP/1.1", r.version());
        assertEquals("example.test", r.host(), "lowercased, port dropped");
        assertEquals("*/*", r.headers().first("accept"), "case-insensitive lookup, value trimmed");
        assertTrue(r.keepAlive());
        assertEquals(BodyMode.NONE, r.bodyMode());
        assertNull(r.body());
    }

    @Test
    void anUnknownMethodIsSyntacticallyFineAndLeftToTheRouter() {
        assertEquals("PUT", parseOk(head("PUT / HTTP/1.1", "Host: x")).method());
        assertEquals("get", parseOk(head("get / HTTP/1.1", "Host: x")).method(), "methods are case-sensitive tokens");
    }

    @Test
    void headersAreCaseInsensitiveAndRepeatedOnesKeepEveryValue() {
        Request r = parseOk(head("GET / HTTP/1.1", "Host: x", "X-Tag: a", "x-tag: b", "X-TAG:   c  "));
        assertEquals(List.of("a", "b", "c"), r.headers().all("X-Tag"));
        assertEquals("a, b, c", r.headers().joined("x-tag"));
        assertEquals(4, r.headers().count());
        assertFalse(r.headers().contains("Nope"));
    }

    @Test
    void aSingleEmptyLineBeforeTheRequestLineIsIgnored() {
        assertEquals("/a", parseOk("\r\n" + head("GET /a HTTP/1.1", "Host: x")).path());
    }

    @Test
    void absoluteFormTargetsUseTheirOwnAuthority() {
        Request r = parseOk(head("GET http://Other.test:9/p/q?z=1 HTTP/1.1", "Host: ignored.test"));
        assertEquals("/p/q", r.path());
        assertEquals("z=1", r.query());
        assertEquals("other.test", r.host());
        assertEquals("/", parseOk(head("GET http://other.test HTTP/1.1", "Host: x")).path());
    }

    @Test
    void http10DoesNotRequireHost() {
        Request r = parseOk(head("GET / HTTP/1.0"));
        assertEquals("", r.host());
        assertFalse(r.keepAlive());
    }

    // ---- persistence ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("keepAliveCases")
    void keepAliveRules(String version, String connectionHeader, boolean expected) {
        String[] headers = connectionHeader == null ? new String[] {"Host: x"} : new String[] {"Host: x", "Connection: " + connectionHeader};
        assertEquals(expected, parseOk(head("GET / " + version, headers)).keepAlive());
    }

    static Stream<Arguments> keepAliveCases() {
        return Stream.of(
                Arguments.of("HTTP/1.1", null, true),
                Arguments.of("HTTP/1.1", "close", false),
                Arguments.of("HTTP/1.1", "CLOSE", false),
                Arguments.of("HTTP/1.1", "keep-alive", true),
                Arguments.of("HTTP/1.1", "keep-alive, close", false),
                Arguments.of("HTTP/1.1", "Upgrade, Close", false),
                Arguments.of("HTTP/1.0", null, false),
                Arguments.of("HTTP/1.0", "keep-alive", true),
                Arguments.of("HTTP/1.0", "Keep-Alive", true),
                Arguments.of("HTTP/1.0", "keep-alive, close", false));
    }

    // ---- path decoding and normalization ---------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("pathCases")
    void pathsAreDecodedOnceAndNormalized(String target, String expected) {
        assertEquals(expected, parseOk(head("GET " + target + " HTTP/1.1", "Host: x")).path(), target);
    }

    static Stream<Arguments> pathCases() {
        return Stream.of(
                Arguments.of("/", "/"),
                Arguments.of("/a", "/a"),
                Arguments.of("/a/", "/a/"),
                Arguments.of("/a/./b", "/a/b"),
                Arguments.of("/a/b/..", "/a/"),
                Arguments.of("/a/b/../", "/a/"),
                Arguments.of("/a/b/../c", "/a/c"),
                Arguments.of("/a/..", "/"),
                Arguments.of("/a/%2e%2e/b", "/b"),
                Arguments.of("//a///b", "/a/b"),
                Arguments.of("/a%20b", "/a b"),
                Arguments.of("/caf%C3%A9", "/caf\u00e9"),
                Arguments.of("/a%2Fb", "/a/b"),
                Arguments.of("/%252e%252e/x", "/%2e%2e/x"),     // decoded exactly once, never twice
                Arguments.of("/a+b", "/a+b"),                   // '+' is only special in query strings
                Arguments.of("/file.txt?name=a/../b", "/file.txt"));
    }

    // ---- the same result however the bytes are split ----------------------------------------------------------

    @Test
    void sameRequestForEverySplitPointGet() throws Exception {
        byte[] data = bytes(head("GET /a/b?x=1 HTTP/1.1", "Host: Site.Test", "Connection: keep-alive", "X: 1"));
        String expected = summary(parseOk(new String(data, StandardCharsets.ISO_8859_1)));
        for (int split = 0; split <= data.length; split++) {
            HttpParser p = parser(NO_LIMIT);
            byte[] a = java.util.Arrays.copyOfRange(data, 0, split);
            byte[] b = java.util.Arrays.copyOfRange(data, split, data.length);
            assertEquals(Status.COMPLETE, feedPieces(p, a, b), "split at " + split);
            assertEquals(expected, summary(p.request()), "split at " + split);
        }
    }

    @Test
    void sameRequestForEverySplitPointFixedBody() throws Exception {
        byte[] body = new byte[300];
        new Random(1).nextBytes(body);
        byte[] data = concat(bytes(head("POST /up HTTP/1.1", "Host: x", "Content-Length: 300")), body);
        HttpParser reference = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(reference, data));
        String expected = summary(reference.request());
        for (int split = 0; split <= data.length; split++) {
            HttpParser p = parser(NO_LIMIT);
            assertEquals(Status.COMPLETE,
                    feedPieces(p, java.util.Arrays.copyOfRange(data, 0, split), java.util.Arrays.copyOfRange(data, split, data.length)),
                    "split at " + split);
            assertEquals(expected, summary(p.request()), "split at " + split);
        }
    }

    @Test
    void sameRequestForEverySplitPointChunkedWithExtensionsAndTrailers() throws Exception {
        byte[] data = bytes(head("POST /c HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")
                .replaceAll("\r\n\r\n$", "\r\n\r\n")
                + "5\r\nhello\r\n"
                + "A ; name=value\r\n0123456789\r\n"
                + "1f\r\n" + "x".repeat(31) + "\r\n"
                + "0\r\nTrailer-One: 1\r\nTrailer-Two: 2\r\n\r\n");
        HttpParser reference = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(reference, data));
        assertEquals("hello0123456789" + "x".repeat(31), new String(readAll(reference.request().body()), StandardCharsets.ISO_8859_1));
        String expected = summary(reference.request());
        for (int split = 0; split <= data.length; split++) {
            HttpParser p = parser(NO_LIMIT);
            assertEquals(Status.COMPLETE,
                    feedPieces(p, java.util.Arrays.copyOfRange(data, 0, split), java.util.Arrays.copyOfRange(data, split, data.length)),
                    "split at " + split);
            assertEquals(expected, summary(p.request()), "split at " + split);
        }
    }

    @Test
    void oneByteAtATimeWorksForEverything() throws Exception {
        byte[] body = new byte[200];
        new Random(2).nextBytes(body);
        byte[] data = concat(bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")), chunked(body, 50));
        HttpParser p = parser(NO_LIMIT);
        Status s = Status.NEED_MORE;
        for (int i = 0; i < data.length; i++) {
            assertEquals(Status.NEED_MORE, s, "finished early at byte " + i);
            s = p.feed(ByteBuffer.wrap(data, i, 1));
        }
        assertEquals(Status.COMPLETE, s);
        assertArrayEquals(body, readAll(p.request().body()));
    }

    // ---- bodies ---------------------------------------------------------------------------------------------------

    @Test
    void binaryBodiesSurviveByteForByte() throws Exception {
        byte[] body = new byte[256 * 8];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;                                  // every byte value, including 0, 10, 13 and 255
        }
        HttpParser fixed = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(fixed,
                concat(bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: " + body.length)), body)));
        assertArrayEquals(body, readAll(fixed.request().body()));

        HttpParser chunkedParser = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(chunkedParser,
                concat(bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")), chunked(body, 100))));
        assertArrayEquals(body, readAll(chunkedParser.request().body()));
        assertEquals(BodyMode.CHUNKED, chunkedParser.request().bodyMode());
    }

    @Test
    void aShortBodyWaitsForMoreAndAnEmptyBodyNeedsNoSink() {
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.NEED_MORE, feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 10") + "12345")));
        assertEquals(Status.COMPLETE, feedPieces(p, bytes("67890")));

        sinksCreated.set(0);
        assertEquals(BodyMode.NONE, parseOk(head("POST / HTTP/1.1", "Host: x", "Content-Length: 0")).bodyMode());
        assertEquals(0, sinksCreated.get());
    }

    @Test
    void bytesAfterTheRequestStayInTheBufferForTheNextOne() throws Exception {
        String first = head("POST /1 HTTP/1.1", "Host: x", "Content-Length: 3") + "abc";
        String second = head("GET /2 HTTP/1.1", "Host: x");
        ByteBuffer buf = ByteBuffer.wrap(bytes(first + second));
        HttpParser p = parser(NO_LIMIT);

        assertEquals(Status.COMPLETE, p.feed(buf));
        assertEquals("/1", p.request().path());
        assertEquals("abc", new String(readAll(p.request().body()), StandardCharsets.ISO_8859_1));
        assertEquals(second.length(), buf.remaining(), "the pipelined request is untouched");

        p.reset();
        assertEquals(Status.COMPLETE, p.feed(buf));
        assertEquals("/2", p.request().path());
        assertFalse(buf.hasRemaining());
    }

    @Test
    void resetWorksAfterEveryKindOfRequest() {
        HttpParser p = parser(NO_LIMIT);
        for (int i = 0; i < 3; i++) {
            assertEquals(Status.COMPLETE, feedPieces(p, bytes(head("GET /" + i + " HTTP/1.1", "Host: x"))));
            p.reset();
            assertEquals(Status.COMPLETE, feedPieces(p, concat(bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")), chunked(new byte[] {1, 2, 3}, 2))));
            p.reset();
        }
    }

    @Test
    void getAndDeleteMayCarryABodyAndItIsConsumed() throws Exception {
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(p, bytes(head("DELETE /x HTTP/1.1", "Host: x", "Content-Length: 4") + "data")));
        assertEquals(4, p.request().body().size());
    }

    // ---- chunked framing errors ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "zz\r\nhello\r\n0\r\n\r\n",          // not hex
            "\r\nhello\r\n0\r\n\r\n",            // empty size
            "-5\r\nhello\r\n0\r\n\r\n",          // sign
            "5x\r\nhello\r\n0\r\n\r\n",          // junk after the digits
            "1234567890123456\r\n",              // 16 hex digits: refused before it can overflow
            "5\r\nhelloXX0\r\n\r\n",             // data not followed by CRLF
            "5\r\nhello\n0\r\n\r\n",             // bare LF after data
            "5\nhello\r\n0\r\n\r\n",             // bare LF after the size
            "5\r\r\nhello\r\n0\r\n\r\n",         // doubled CR
            "5\r\nhello\r\n0\r\nbad\n\r\n"       // bare LF inside a trailer
    })
    void malformedChunkFramingIsA400(String body) {
        ParseError e = parseError(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked") + body);
        assertEquals(400, e.status(), e.message());
    }

    @Test
    void chunkSizesAcceptEitherCaseAndExtensions() throws Exception {
        String body = "A\r\n0123456789\r\n" + "a;ext=1\r\n0123456789\r\n" + "0A ; x\r\n0123456789\r\n0\r\n\r\n";
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked") + body)));
        assertEquals(30, p.request().body().size());
    }

    // ---- limits ---------------------------------------------------------------------------------------------------------

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedBeforeAnyBodyIsRead() {
        HttpParser p = parser(1000);
        Status s = feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 1001")));
        assertEquals(Status.ERROR, s);
        assertEquals(413, p.error().status());
        assertEquals(0, sinksCreated.get(), "no sink (temp file) is created for a refused request");
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() {
        HttpParser p = parser(10);
        assertEquals(Status.COMPLETE, feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 10") + "0123456789")));
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedAtTheChunkHeaderAlready() {
        HttpParser p = parser(100);
        // 3 chunks of 40 bytes: the third pushes the total to 120. The refusal comes when its size line
        // arrives, before the data does.
        byte[] data = concat(
                bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")),
                bytes("28\r\n" + "a".repeat(40) + "\r\n"),
                bytes("28\r\n" + "b".repeat(40) + "\r\n"),
                bytes("28\r\n"));
        assertEquals(Status.ERROR, feedPieces(p, data));
        assertEquals(413, p.error().status());
    }

    @Test
    void aChunkedBodyExactlyAtTheLimitIsAccepted() {
        HttpParser p = parser(80);
        byte[] data = concat(bytes(head("POST / HTTP/1.1", "Host: x", "Transfer-Encoding: chunked")),
                bytes("28\r\n" + "a".repeat(40) + "\r\n28\r\n" + "b".repeat(40) + "\r\n0\r\n\r\n"));
        assertEquals(Status.COMPLETE, feedPieces(p, data));
    }

    @Test
    void anOversizedHeadIsRefused() {
        HttpParser p = new HttpParser(128, req -> HeadDecision.accept(NO_LIMIT, MemoryBodySink::new));
        assertEquals(Status.ERROR, feedPieces(p, bytes(head("GET / HTTP/1.1", "Host: x", "X: " + "a".repeat(500)))));
        assertEquals(400, p.error().status());
    }

    // ---- Expect: 100-continue ---------------------------------------------------------------------------------------------

    @Test
    void expectContinueAsksForTheInterimResponseOnceAndThenReadsTheBody() throws Exception {
        HttpParser p = parser(NO_LIMIT);
        ByteBuffer headBuf = ByteBuffer.wrap(bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 5", "Expect: 100-continue")));
        assertEquals(Status.SEND_CONTINUE, p.feed(headBuf));
        assertEquals(Status.NEED_MORE, p.feed(headBuf), "asked only once");
        assertEquals(Status.COMPLETE, p.feed(ByteBuffer.wrap(bytes("hello"))));
        assertEquals("hello", new String(readAll(p.request().body()), StandardCharsets.ISO_8859_1));
    }

    @Test
    void expectContinueWithoutABodyOrOnHttp10IsIgnored() {
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.COMPLETE, p.feed(ByteBuffer.wrap(bytes(head("GET / HTTP/1.1", "Host: x", "Expect: 100-continue")))));
        assertFalse(p.request().expectContinue());
    }

    @Test
    void anOverlargeRequestWithExpectContinueIsRefusedWithoutSendingContinue() {
        HttpParser p = parser(10);
        Status s = p.feed(ByteBuffer.wrap(bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 99", "Expect: 100-continue"))));
        assertEquals(Status.ERROR, s);
        assertEquals(413, p.error().status());
    }

    // ---- the listener seam ---------------------------------------------------------------------------------------------------

    @Test
    void theListenerCanRefuseARequestBeforeItsBodyIsRead() {
        AtomicInteger calls = new AtomicInteger();
        HttpParser p = new HttpParser(1024, req -> {
            calls.incrementAndGet();
            return HeadDecision.reject(405, "method not allowed here");
        });
        assertEquals(Status.ERROR, feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 5"))));
        assertEquals(405, p.error().status());
        assertEquals("method not allowed here", p.error().message());
        assertEquals(1, calls.get());
        assertEquals(Status.ERROR, p.feed(ByteBuffer.wrap(bytes("hello"))), "the failure is sticky");
    }

    @Test
    void theListenerSeesTheParsedHeadAndChoosesTheLimit() {
        List<String> seen = new ArrayList<>();
        HttpParser p = new HttpParser(1024, req -> {
            seen.add(req.host() + " " + req.path());
            return HeadDecision.accept(req.host().equals("small.test") ? 3 : 100, MemoryBodySink::new);
        });
        assertEquals(Status.ERROR, feedPieces(p, bytes(head("POST /x HTTP/1.1", "Host: small.test", "Content-Length: 5") + "hello")));
        assertEquals(List.of("small.test /x"), seen);
        assertEquals(413, p.error().status());
    }

    @Test
    void aFailingSinkBecomesA500() {
        HttpParser p = new HttpParser(1024, req -> HeadDecision.accept(NO_LIMIT, () -> {
            throw new IOException("disk full");
        }));
        assertEquals(Status.ERROR, feedPieces(p, bytes(head("POST / HTTP/1.1", "Host: x", "Content-Length: 5") + "hello")));
        assertEquals(500, p.error().status());
    }

    // ---- everything that must be a 400 --------------------------------------------------------------------------------------

    static Stream<Arguments> malformedRequests() {
        String h = "Host: x";
        return Stream.of(
                Arguments.of("two-part request line", "GET /\r\n" + h + "\r\n\r\n"),
                Arguments.of("four-part request line", "GET / HTTP/1.1 extra\r\n" + h + "\r\n\r\n"),
                Arguments.of("double space", "GET  / HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("HTTP/2.0", "GET / HTTP/2.0\r\n" + h + "\r\n\r\n"),
                Arguments.of("HTTP/0.9", "GET / HTTP/0.9\r\n" + h + "\r\n\r\n"),
                Arguments.of("lowercase version", "GET / http/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("illegal method character", "GE(T / HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("empty request", "\r\n\r\n"),
                Arguments.of("missing Host", "GET / HTTP/1.1\r\n\r\n"),
                Arguments.of("empty Host", "GET / HTTP/1.1\r\nHost:\r\n\r\n"),
                Arguments.of("two Hosts", "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"),
                Arguments.of("header without colon", "GET / HTTP/1.1\r\n" + h + "\r\nNoColon\r\n\r\n"),
                Arguments.of("space before colon", "GET / HTTP/1.1\r\nHost : x\r\n\r\n"),
                Arguments.of("space inside a name", "GET / HTTP/1.1\r\n" + h + "\r\nBad Name: v\r\n\r\n"),
                Arguments.of("empty header name", "GET / HTTP/1.1\r\n" + h + "\r\n: v\r\n\r\n"),
                Arguments.of("obs-fold with space", "GET / HTTP/1.1\r\n" + h + "\r\n folded\r\n\r\n"),
                Arguments.of("obs-fold with tab", "GET / HTTP/1.1\r\n" + h + "\r\n\tfolded\r\n\r\n"),
                Arguments.of("control char in value", "GET / HTTP/1.1\r\n" + h + "\r\nX: a\u0001b\r\n\r\n"),
                Arguments.of("NUL in value", "GET / HTTP/1.1\r\n" + h + "\r\nX: a\u0000b\r\n\r\n"),
                Arguments.of("bare LF in value", "GET / HTTP/1.1\r\n" + h + "\r\nX: a\nb: c\r\n\r\n"),
                Arguments.of("bare CR in value", "GET / HTTP/1.1\r\n" + h + "\r\nX: a\rb\r\n\r\n"),
                Arguments.of("CL with plus", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: +5\r\n\r\n"),
                Arguments.of("CL negative", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: -1\r\n\r\n"),
                Arguments.of("CL list", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 5, 5\r\n\r\n"),
                Arguments.of("CL hex", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 0x10\r\n\r\n"),
                Arguments.of("CL empty", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length:\r\n\r\n"),
                Arguments.of("CL too many digits", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 99999999999999999999\r\n\r\n"),
                Arguments.of("CL conflicting", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n"),
                Arguments.of("CL and TE", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n"),
                Arguments.of("TE gzip", "POST / HTTP/1.1\r\n" + h + "\r\nTransfer-Encoding: gzip\r\n\r\n"),
                Arguments.of("TE gzip chunked", "POST / HTTP/1.1\r\n" + h + "\r\nTransfer-Encoding: gzip, chunked\r\n\r\n"),
                Arguments.of("TE on HTTP/1.0", "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n"),
                Arguments.of("unsupported Expect", "POST / HTTP/1.1\r\n" + h + "\r\nContent-Length: 1\r\nExpect: 102-processing\r\n\r\n"),
                Arguments.of("target without slash", "GET foo HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("asterisk target", "OPTIONS * HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("fragment in target", "GET /a#frag HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("bad percent sequence", "GET /%zz HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("truncated percent", "GET /a% HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("truncated percent 2", "GET /a%4 HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("encoded NUL", "GET /a%00b HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("encoded newline", "GET /a%0Ab HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("backslash", "GET /a\\b HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("encoded backslash", "GET /a%5Cb HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("invalid UTF-8", "GET /%ff HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("climbs above root", "GET /../x HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("encoded climb", "GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("climb via encoded slash", "GET /..%2f..%2fetc HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("climb in the middle", "GET /a/../../x HTTP/1.1\r\n" + h + "\r\n\r\n"),
                Arguments.of("garbage bytes", "\u0000\u0001\u0002\u00ff\u00fe\r\n\r\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRequests")
    void malformedRequestsAreRefusedWith400(String label, String raw) {
        ParseError e = parseError(raw);
        assertEquals(400, e.status(), label + ": " + e.message());
        assertNotNull(e.message());
    }

    @Test
    void tooManyHeadersIsA400() {
        String[] headers = new String[HeadParser.MAX_HEADERS + 1];
        headers[0] = "Host: x";
        for (int i = 1; i < headers.length; i++) {
            headers[i] = "X-" + i + ": v";
        }
        assertEquals(400, parseError(head("GET / HTTP/1.1", headers)).status());

        String[] ok = new String[HeadParser.MAX_HEADERS];
        ok[0] = "Host: x";
        for (int i = 1; i < ok.length; i++) {
            ok[i] = "X-" + i + ": v";
        }
        assertEquals(Status.COMPLETE, feedPieces(parser(NO_LIMIT), bytes(head("GET / HTTP/1.1", ok))));
    }

    @Test
    void afterAnErrorTheParserStaysFailedUntilReset() {
        HttpParser p = parser(NO_LIMIT);
        assertEquals(Status.ERROR, feedPieces(p, bytes("GARBAGE\r\n\r\n")));
        assertEquals(Status.ERROR, p.feed(ByteBuffer.wrap(bytes(head("GET / HTTP/1.1", "Host: x")))));
        p.reset();
        assertEquals(Status.COMPLETE, feedPieces(p, bytes(head("GET / HTTP/1.1", "Host: x"))));
    }

    @Test
    void startedReflectsWhetherARequestIsInProgress() {
        HttpParser p = parser(NO_LIMIT);
        assertFalse(p.started());
        p.feed(ByteBuffer.wrap(bytes("GET / HT")));
        assertTrue(p.started());
        feedPieces(p, bytes("TP/1.1\r\nHost: x\r\n\r\n"));
        p.reset();
        assertFalse(p.started());
    }
}
