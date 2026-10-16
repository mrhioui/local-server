package localserver.http;

/**
 * A parsed request. Everything except the body is immutable and fixed once the head is parsed;
 * the body sink is attached by the parser as soon as the head is accepted and is complete when
 * the parser reports COMPLETE.
 */
public final class Request {

    public enum BodyMode { NONE, FIXED, CHUNKED }

    private final String method;
    private final String target;
    private final String path;
    private final String query;
    private final String version;
    private final String host;
    private final Headers headers;
    private final boolean keepAlive;
    private final BodyMode bodyMode;
    private final long contentLength;
    private final boolean expectContinue;
    private BodySink body;

    public static Request of(String method, String path, String host) {
        return of(method, path, host, null, null);
    }

    public static Request of(String method, String target, String host, Headers headers, BodySink body) {
        Headers h = headers != null ? headers : new Headers();
        if (host != null && h.first("Host") == null) {
            h.add("Host", host);
        }
        String p = target;
        String q = "";
        int qIdx = target != null ? target.indexOf('?') : -1;
        if (qIdx >= 0) {
            p = target.substring(0, qIdx);
            q = target.substring(qIdx + 1);
        }
        Request r = new Request(method, target != null ? target : "", p != null ? p : "", q, "HTTP/1.1",
                host != null ? host : "", h, true,
                body != null ? BodyMode.FIXED : BodyMode.NONE, body != null ? body.size() : 0, false);
        r.attachBody(body);
        return r;
    }


    Request(String method, String target, String path, String query, String version, String host,
            Headers headers, boolean keepAlive, BodyMode bodyMode, long contentLength, boolean expectContinue) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.query = query;
        this.version = version;
        this.host = host;
        this.headers = headers;
        this.keepAlive = keepAlive;
        this.bodyMode = bodyMode;
        this.contentLength = contentLength;
        this.expectContinue = expectContinue;
    }

    /** Method exactly as sent (case-sensitive token); unknown methods are the router's business. */
    public String method() {
        return method;
    }

    /** The request target as sent, e.g. "/a%20b/../c?x=1". */
    public String target() {
        return target;
    }

    /** Percent-decoded (once) and dot-segment-normalized path, always starting with '/'. */
    public String path() {
        return path;
    }

    /** Raw query string without the '?', "" when absent. */
    public String query() {
        return query;
    }

    /** "HTTP/1.1" or "HTTP/1.0". */
    public String version() {
        return version;
    }

    /** Lowercase host name without port, used to pick the virtual host; "" when unknown. */
    public String host() {
        return host;
    }

    public Headers headers() {
        return headers;
    }

    /** Whether the connection may stay open after the response. */
    public boolean keepAlive() {
        return keepAlive;
    }

    public BodyMode bodyMode() {
        return bodyMode;
    }

    /** Declared Content-Length for FIXED bodies; 0 otherwise (a chunked body has no declared size). */
    public long contentLength() {
        return contentLength;
    }

    public boolean expectContinue() {
        return expectContinue;
    }

    /** The decoded body (null when the request has none). Chunked bodies arrive de-chunked. */
    public BodySink body() {
        return body;
    }

    void attachBody(BodySink sink) {
        this.body = sink;
    }
}
