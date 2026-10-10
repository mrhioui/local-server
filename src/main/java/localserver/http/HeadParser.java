package localserver.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import localserver.config.ListenerSpec;
import localserver.http.Request.BodyMode;

/**
 * Turns the bytes of a complete request head into a validated {@link Request}.
 *
 * <p>Deliberately strict. A server that is lenient about framing (which headers say where the body
 * ends) can be tricked into reading a different request than the one the client "sent", so anything
 * ambiguous is a 400 here. The head is decoded as ISO-8859-1 so every byte maps to exactly one
 * character; the path is later decoded as UTF-8 only after percent-decoding.
 */
final class HeadParser {

    static final int MAX_HEADERS = 100;
    private static final int MAX_METHOD = 32;
    private static final int MAX_TARGET = 8000;
    private static final int MAX_LENGTH_DIGITS = 18;   // always fits in a long

    private HeadParser() { }

    static Request parse(byte[] raw) throws HttpException {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int start = text.startsWith("\r\n") ? 2 : 0;       // tolerate one empty line before the request line
        int end = text.length() - 4;                       // drop the final CRLF CRLF
        if (end <= start) {
            throw new HttpException(400, "empty request");
        }
        String[] lines = text.substring(start, end).split("\r\n", -1);
        for (String line : lines) {
            if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) {
                throw new HttpException(400, "bare CR or LF in the request head");
            }
        }

        // ---- request line ----------------------------------------------------------------
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new HttpException(400, "malformed request line");
        }
        String method = parts[0];
        if (method.length() > MAX_METHOD || !isToken(method)) {
            throw new HttpException(400, "invalid method");
        }
        String target = parts[1];
        if (target.length() > MAX_TARGET) {
            throw new HttpException(400, "request target too long");
        }
        String version = parts[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpException(400, "unsupported HTTP version");
        }
        boolean http11 = version.equals("HTTP/1.1");
        Target parsedTarget = parseTarget(target);

        // ---- header fields -----------------------------------------------------------------
        Headers headers = new Headers();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                throw new HttpException(400, "empty header line");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpException(400, "obsolete line folding is not accepted");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "malformed header line");
            }
            String name = line.substring(0, colon);          // no trimming: "Host :" must be rejected
            if (!isToken(name)) {
                throw new HttpException(400, "invalid header name");
            }
            String value = trimOws(line.substring(colon + 1));
            for (int k = 0; k < value.length(); k++) {
                char c = value.charAt(k);
                if (c != '\t' && (c < 0x20 || c == 0x7F)) {
                    throw new HttpException(400, "control character in a header value");
                }
            }
            if (headers.count() >= MAX_HEADERS) {
                throw new HttpException(400, "too many header fields");
            }
            headers.add(name, value);
        }

        // ---- Host --------------------------------------------------------------------------
        List<String> hosts = headers.all("Host");
        if (hosts.size() > 1) {
            throw new HttpException(400, "multiple Host headers");
        }
        if (http11 && (hosts.isEmpty() || hosts.get(0).isEmpty())) {
            throw new HttpException(400, "missing Host header");
        }
        String hostValue = hosts.isEmpty() ? "" : hosts.get(0);
        String host = ListenerSpec.normalizeHost(parsedTarget.authority() != null ? parsedTarget.authority() : hostValue);

        // ---- persistence -------------------------------------------------------------------
        boolean closeToken = false;
        boolean keepToken = false;
        for (String value : headers.all("Connection")) {
            for (String token : value.split(",")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.equals("close")) {
                    closeToken = true;
                } else if (t.equals("keep-alive")) {
                    keepToken = true;
                }
            }
        }
        boolean keepAlive = http11 ? !closeToken : keepToken && !closeToken;

        // ---- where does the body end? (RFC 9112 section 6) ---------------------------------------
        List<String> lengths = headers.all("Content-Length");
        List<String> codings = headers.all("Transfer-Encoding");
        BodyMode mode = BodyMode.NONE;
        long contentLength = 0;
        if (!codings.isEmpty()) {
            if (!http11) {
                throw new HttpException(400, "Transfer-Encoding is not allowed in HTTP/1.0");
            }
            if (!lengths.isEmpty()) {
                throw new HttpException(400, "both Content-Length and Transfer-Encoding");
            }
            if (!String.join(",", codings).trim().equalsIgnoreCase("chunked")) {
                throw new HttpException(400, "unsupported Transfer-Encoding");
            }
            mode = BodyMode.CHUNKED;
        } else if (!lengths.isEmpty()) {
            long declared = -1;
            for (String value : lengths) {
                long n = parseLength(value);
                if (n < 0) {
                    throw new HttpException(400, "invalid Content-Length");
                }
                if (declared >= 0 && declared != n) {
                    throw new HttpException(400, "conflicting Content-Length headers");
                }
                declared = n;
            }
            contentLength = declared;
            mode = declared == 0 ? BodyMode.NONE : BodyMode.FIXED;
        }

        // ---- Expect --------------------------------------------------------------------------------
        boolean expectContinue = false;
        String expect = headers.first("Expect");
        if (expect != null && http11) {
            if (!expect.trim().equalsIgnoreCase("100-continue")) {
                throw new HttpException(400, "unsupported Expect");
            }
            expectContinue = mode != BodyMode.NONE;
        }

        return new Request(method, target, parsedTarget.path(), parsedTarget.query(), version, host,
                headers, keepAlive, mode, contentLength, expectContinue);
    }

    // ---- request target ------------------------------------------------------------------------------

    private record Target(String path, String query, String authority) { }

    private static Target parseTarget(String target) throws HttpException {
        String pathAndQuery;
        String authority = null;
        if (target.startsWith("/")) {
            pathAndQuery = target;
        } else {
            // absolute-form ("GET http://host/path"): legal, and the authority wins over Host
            String lower = target.toLowerCase(Locale.ROOT);
            int schemeLength = lower.startsWith("http://") ? 7 : lower.startsWith("https://") ? 8 : 0;
            if (schemeLength == 0) {
                throw new HttpException(400, "unsupported request target");
            }
            int i = schemeLength;
            while (i < target.length() && target.charAt(i) != '/' && target.charAt(i) != '?') {
                i++;
            }
            authority = target.substring(schemeLength, i);
            if (authority.isEmpty()) {
                throw new HttpException(400, "missing host in request target");
            }
            String rest = target.substring(i);
            pathAndQuery = rest.isEmpty() ? "/" : rest.startsWith("?") ? "/" + rest : rest;
        }
        if (pathAndQuery.indexOf('#') >= 0) {
            throw new HttpException(400, "fragment in request target");
        }
        for (int i = 0; i < pathAndQuery.length(); i++) {
            char c = pathAndQuery.charAt(i);
            if (c < 0x21 || c == 0x7F) {
                throw new HttpException(400, "invalid character in request target");
            }
        }
        int q = pathAndQuery.indexOf('?');
        String rawPath = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? "" : pathAndQuery.substring(q + 1);
        return new Target(normalize(decode(rawPath)), query, authority);
    }

    /** Percent-decodes exactly once, then requires valid UTF-8 without NUL, control chars or '\'. */
    static String decode(String raw) throws HttpException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new HttpException(400, "truncated percent-encoding in the path");
                }
                int hi = hexValue(raw.charAt(i + 1));
                int lo = hexValue(raw.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new HttpException(400, "invalid percent-encoding in the path");
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                out.write(c);   // the head was decoded as ISO-8859-1, so c is a single byte
            }
        }
        byte[] bytes = out.toByteArray();
        for (byte b : bytes) {
            if (b == 0 || (b >= 0 && b < 0x20) || b == 0x7F || b == '\\') {
                throw new HttpException(400, "forbidden character in the path");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new HttpException(400, "the path is not valid UTF-8");
        }
    }

    /**
     * Resolves "." and ".." segments and collapses repeated slashes (RFC 3986 style). A path that
     * would climb above the root is rejected. Runs on the DECODED path, so "%2e%2e" and "..%2f"
     * cannot smuggle a traversal past it.
     */
    static String normalize(String path) throws HttpException {
        String[] segments = path.split("/", -1);          // "/a/b/" -> ["", "a", "b", ""]
        Deque<String> kept = new ArrayDeque<>();
        boolean trailingSlash = false;
        for (int i = 1; i < segments.length; i++) {
            String s = segments[i];
            boolean last = i == segments.length - 1;
            if (s.equals(".")) {
                trailingSlash |= last;
            } else if (s.equals("..")) {
                if (kept.isEmpty()) {
                    throw new HttpException(400, "the path climbs above the root");
                }
                kept.removeLast();
                trailingSlash |= last;
            } else if (s.isEmpty()) {
                trailingSlash |= last;
            } else {
                kept.addLast(s);
            }
        }
        String result = "/" + String.join("/", kept);
        return trailingSlash && !kept.isEmpty() ? result + "/" : result;
    }

    // ---- small helpers -------------------------------------------------------------------------------------

    /** Content-Length value: digits only (no sign, no list, no hex), short enough to fit a long. */
    private static long parseLength(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.length() > MAX_LENGTH_DIGITS) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String trimOws(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
            b--;
        }
        return s.substring(a, b);
    }

    static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
