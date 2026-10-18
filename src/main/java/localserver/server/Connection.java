package localserver.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import localserver.config.ListenerSpec;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.config.Settings;
import localserver.handler.CgiHandler;
import localserver.handler.DeleteHandler;
import localserver.handler.ErrorPages;
import localserver.handler.MetricsHandler;
import localserver.handler.RedirectHandler;
import localserver.handler.StaticHandler;
import localserver.handler.UploadHandler;
import localserver.http.BodySink;
import localserver.http.FileBodySink;
import localserver.http.HeadDecision;
import localserver.http.HttpException;
import localserver.http.HttpParser;
import localserver.http.MemoryBodySink;
import localserver.http.ParseError;
import localserver.http.Request;
import localserver.http.Response;
import localserver.http.Status;
import localserver.session.Cookies;
import localserver.session.Session;

/**
 * Everything the server knows about one client: its buffers, its progress through the current
 * request, and its clocks. A non-blocking server cannot keep this on a thread's stack, so it lives
 * here, attached to the connection's SelectionKey.
 *
 * <p>Rules this class follows (they are what the audit checks):
 * <ul>
 *   <li>one read OR one write per readiness event, and every return value is checked;</li>
 *   <li>read interest is only set while reading, write interest only while a response is pending;</li>
 *   <li>any failure ends in {@link Server#close(SelectionKey)}, which is idempotent.</li>
 * </ul>
 *
 * <p>States: READING (the parser consumes head and body) -> WRITING -> READING again (keep-alive),
 * closed, or LINGERING. Lingering is for refused requests: the client may still be sending a body we
 * will never read, and closing a socket that has unread incoming data makes TCP send a reset that can
 * destroy the error response we just wrote. So we send the response, shut down our sending side, and
 * briefly read and discard until the client finishes or a small budget runs out.
 *
 * <p>Until the router arrives (M4), every valid request is answered by echoing what was parsed.
 */
final class Connection {

    private enum State { READING, WRITING, CGI_WAITING, LINGERING }

    /** What to do once the queued bytes are fully written. */
    private enum After { NEXT_REQUEST, RESUME_PARSING, CLOSE, LINGER }

    private static final int MEMORY_BODY_LIMIT = 64 * 1024;      // bigger bodies go to a temp file
    private static final long LINGER_NANOS = 2_000_000_000L;
    private static final long LINGER_MAX_BYTES = 1024 * 1024;
    private static final List<String> SUPPORTED_METHODS = List.of("GET", "POST", "DELETE");

    private final Server server;
    private final SelectionKey key;
    private final SocketChannel channel;
    private final ListenerSpec listener;

    private final ByteBuffer in = ByteBuffer.allocate(8192);     // in "fill" mode between events
    private final HttpParser parser;
    private ByteBuffer out;
    private State state = State.READING;
    private After after = After.NEXT_REQUEST;
    private ServerConfig selected;                               // virtual host of the current request

    private CgiJob cgiJob;
    private Request cgiRequest;
    private boolean cgiKeepAlive;
    private After cgiAfter;

    private long lastActivity;   // last time bytes actually moved
    private long requestStart;   // first byte of the current request
    private long lingerStart;
    private long lingered;

    Connection(Server server, SelectionKey key, SocketChannel channel, ListenerSpec listener, Settings settings) {
        this.server = server;
        this.key = key;
        this.channel = channel;
        this.listener = listener;
        this.parser = new HttpParser(settings.maxHeaderBytes(), this::onHead);
        this.lastActivity = server.now();
    }

    // ---- readiness events ----------------------------------------------------------------

    void onReadable() throws IOException {
        if (state == State.LINGERING) {
            drain();
            return;
        }
        if (state == State.CGI_WAITING) {
            return;
        }
        int n = channel.read(in);
        if (n < 0) {                 // peer closed its side
            server.close(key);
            return;
        }
        if (n == 0) {                // spurious wake-up: nothing to do
            return;
        }
        lastActivity = server.now();
        pump();
    }

    void onWritable() throws IOException {
        int n = channel.write(out);  // may write only part of the buffer; that is normal
        if (n > 0) {
            lastActivity = server.now();
        }
        if (out.hasRemaining()) {
            return;                  // keep OP_WRITE, continue on the next event
        }
        out = null;
        switch (after) {
            case RESUME_PARSING -> {             // the interim "100 Continue" is out: go back to the body
                state = State.READING;
                key.interestOps(SelectionKey.OP_READ);
                if (in.position() > 0) {
                    pump();
                }
            }
            case NEXT_REQUEST -> {               // keep-alive: ready for the next request
                server.requestCompleted();
                parser.reset();                  // also releases this request's body (temp file)
                selected = null;
                requestStart = 0;
                lastActivity = server.now();
                state = State.READING;
                key.interestOps(SelectionKey.OP_READ);
                if (in.position() > 0) {
                    pump();                      // a pipelined request is already buffered: no I/O needed
                }
            }
            case CLOSE -> {
                server.requestCompleted();
                server.close(key);
            }
            case LINGER -> {
                server.requestCompleted();
                beginLinger();
            }
        }
    }

    // ---- request handling ----------------------------------------------------------------

    /** Consumes buffered input. Does no socket I/O. */
    private void pump() {
        in.flip();
        try {
            if (!parser.started() && in.hasRemaining()) {
                requestStart = server.now();
            }
            switch (parser.feed(in)) {
                case NEED_MORE -> { }
                case SEND_CONTINUE -> respond(interimContinue(), After.RESUME_PARSING);
                case COMPLETE -> handle(parser.request());
                case ERROR -> refuse(parser.error());
            }
        } finally {
            in.compact();            // keep any unconsumed bytes for the next round
        }
    }

    /**
     * Called by the parser after the head, before any body byte. Picks the virtual host, so the body
     * limit is that server's. M4 adds route matching here: 404 and 405 are answered without reading a
     * body at all.
     */
    private HeadDecision onHead(Request request) {
        selected = listener.select(request.host());
        return HeadDecision.accept(selected.maxBodySize(), () ->
                request.bodyMode() == Request.BodyMode.FIXED && request.contentLength() <= MEMORY_BODY_LIMIT
                        ? new MemoryBodySink()
                        : FileBodySink.create(server.tempDir()));
    }

    /** Routes and answers a complete, valid request. */
    private void handle(Request request) {
        boolean keepAlive = request.keepAlive();
        After next = keepAlive ? After.NEXT_REQUEST : After.CLOSE;
        if (!SUPPORTED_METHODS.contains(request.method())) {
            Response err = ErrorPages.render(405, selected);
            err.addHeader("Allow", "GET, POST, DELETE");
            sendResponse(err, request, keepAlive, next);
            return;
        }

        if (request.path().equals("/metrics") || request.path().equals("/admin/metrics")) {
            Response res = new MetricsHandler(server).handle(request, null);
            sendResponse(res, request, keepAlive, next);
            return;
        }

        Route route = selected != null ? selected.matchRoute(request.path()) : null;
        if (route == null) {
            sendResponse(ErrorPages.render(404, selected), request, keepAlive, next);
            return;
        }

        if (!route.allows(request.method())) {
            Response err = ErrorPages.render(405, selected);
            err.addHeader("Allow", route.allowHeader());
            sendResponse(err, request, keepAlive, next);
            return;
        }

        // 1. Redirect
        if (route.redirect() != null) {
            Response res = new RedirectHandler().handle(request, route);
            sendResponse(res, request, keepAlive, next);
            return;
        }

        // 2. Relative path for CGI / Static / Upload / Delete
        String rel = extractRel(request.path(), route.path());

        // 3. CGI
        String scriptName = rel.isEmpty() && route.index() != null ? route.index() : rel;
        if (route.interpreterFor(scriptName) != null) {
            try {
                CgiHandler cgiHandler = new CgiHandler(server.tempDir(), 10_000_000_000L);
                CgiJob job = cgiHandler.prepare(request, route);
                startCgi(job, request, keepAlive, next);
            } catch (HttpException e) {
                Response err = ErrorPages.render(e.status(), selected);
                sendResponse(err, request, false, After.CLOSE);
            }
            return;
        }

        // 4. Upload (POST)
        if (request.method().equals("POST")) {
            Response res = new UploadHandler().handle(request, route);
            sendResponse(res, request, keepAlive, next);
            return;
        }

        // 5. Delete (DELETE)
        if (request.method().equals("DELETE")) {
            Response res = new DeleteHandler().handle(request, route);
            sendResponse(res, request, keepAlive, next);
            return;
        }

        // 6. Static files (GET)
        if (request.method().equals("GET")) {
            Response res = new StaticHandler().handle(request, route);
            sendResponse(res, request, keepAlive, next);
            return;
        }

        sendResponse(ErrorPages.render(405, selected), request, keepAlive, next);
    }

    private void sendResponse(Response res, Request request, boolean keepAlive, After defaultNext) {
        After then = defaultNext;
        if (res.getStatusCode() == 400 || res.getStatusCode() == 413) {
            keepAlive = false;
            then = After.LINGER;
        } else if (!keepAlive) {
            then = After.CLOSE;
        }
        respond(wrapResponse(res, request, keepAlive), then);
    }

    private byte[] wrapResponse(Response res, Request request, boolean keepAlive) {
        if (res.getStatusCode() >= 400 && selected != null && selected.errorPages() != null && selected.errorPages().containsKey(res.getStatusCode())) {
            Response custom = ErrorPages.render(res.getStatusCode(), selected);
            res.setBody(custom.getBody(), custom.getHeaders().getOrDefault("Content-Type", "text/html; charset=utf-8"));
        }
        res.addHeader("Connection", keepAlive ? "keep-alive" : "close");
        res.addHeader("Date", server.date());
        ensureSession(res, request);
        return res.getBytes();
    }

    private byte[] renderError(int code, boolean keepAlive) {
        Response err = ErrorPages.render(code, selected);
        return wrapResponse(err, null, keepAlive);
    }

    private void ensureSession(Response res, Request request) {
        if (request == null) return;
        String cookieHeader = request.headers().first("cookie");
        Map<String, String> cookies = Cookies.parse(cookieHeader);
        String sid = cookies.get("SID");
        if (sid == null || Session.getSession(sid) == null) {
            sid = Session.createSession();
            res.addSetCookie(Cookies.formatSetCookie("SID", sid, "/", true, Session.TTL_SECONDS));
        }
    }

    private static String extractRel(String reqPath, String prefix) {
        String rel;
        if (prefix.equals("/")) {
            rel = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
        } else if (reqPath.equals(prefix)) {
            rel = "";
        } else if (reqPath.startsWith(prefix + "/")) {
            rel = reqPath.substring(prefix.length() + 1);
        } else {
            rel = reqPath.substring(prefix.length());
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
        }
        return rel;
    }

    /** A request we will not serve (malformed, too large, refused): answer, then linger and close. */
    private void refuse(ParseError error) {
        Response err = ErrorPages.render(error.status(), selected);
        err.addHeader("Connection", "close");
        err.addHeader("Date", server.date());
        respond(err.getBytes(), After.LINGER);
    }

    private void respond(byte[] bytes, After then) {
        out = ByteBuffer.wrap(bytes);
        after = then;
        state = State.WRITING;
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private static byte[] interimContinue() {
        return "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    }

    // ---- lingering close -------------------------------------------------------------------

    private void beginLinger() throws IOException {
        channel.shutdownOutput();    // the client now sees the end of our response
        state = State.LINGERING;
        lingerStart = server.now();
        lastActivity = lingerStart;
        key.interestOps(SelectionKey.OP_READ);
    }

    /** Reads and throws away whatever the client is still sending. */
    private void drain() throws IOException {
        in.clear();
        int n = channel.read(in);
        in.clear();
        if (n < 0) {
            server.close(key);       // the client finished: a clean close, no reset
            return;
        }
        lingered += n;
        if (lingered > LINGER_MAX_BYTES) {
            server.close(key);       // not going to absorb an unbounded upload
        }
    }


    // ---- CGI execution ---------------------------------------------------------------------

    private void startCgi(CgiJob job, Request req, boolean keepAlive, After next) {
        this.cgiJob = job;
        this.cgiRequest = req;
        this.cgiKeepAlive = keepAlive;
        this.cgiAfter = next;
        this.state = State.CGI_WAITING;
        this.key.interestOps(0);
        server.registerCgiWaiter(this);
    }

    void pollCgi(long now) {
        if (state != State.CGI_WAITING || cgiJob == null) {
            return;
        }
        if (cgiJob.isTimedOut(now)) {
            cgiJob.destroy();
            cgiJob = null;
            server.unregisterCgiWaiter(this);
            Response err = ErrorPages.render(504, selected);
            sendResponse(err, cgiRequest, cgiKeepAlive, cgiAfter);
            return;
        }
        if (cgiJob.isDone()) {
            server.unregisterCgiWaiter(this);
            try {
                Response res = cgiJob.finish();
                cgiJob = null;
                sendResponse(res, cgiRequest, cgiKeepAlive, cgiAfter);
            } catch (HttpException e) {
                cgiJob = null;
                Response err = ErrorPages.render(e.status(), selected);
                sendResponse(err, cgiRequest, false, After.CLOSE);
            }
        }
    }

    // ---- timeouts and cleanup --------------------------------------------------------------

    /**
     * Why this connection should be closed now, or null if it is fine. Two clocks:
     * inactivity (nothing moving) and the per-request deadline (stops slow trickling).
     */
    String timeoutReason(long now, Settings s) {
        if (state == State.LINGERING) {
            return now - lingerStart > LINGER_NANOS ? "linger over" : null;
        }
        if (state == State.CGI_WAITING) {
            return null;
        }
        long quiet = now - lastActivity;
        boolean inRequest = state == State.WRITING || parser.started();
        if (!inRequest) {
            return quiet > millis(s.keepAliveTimeoutMs()) ? "idle" : null;
        }
        if (quiet > millis(s.idleTimeoutMs())) {
            return "stalled";
        }
        if (now - requestStart > millis(s.requestTimeoutMs())) {
            return "request too slow";
        }
        return null;
    }

    /** Frees whatever the connection holds besides the channel: the body temp file, later a CGI process. */
    void releaseResources() {
        out = null;
        if (cgiJob != null) {
            cgiJob.destroy();
            cgiJob = null;
            server.unregisterCgiWaiter(this);
        }
        parser.close();
    }

    private static long millis(int ms) {
        return ms * 1_000_000L;
    }
}
