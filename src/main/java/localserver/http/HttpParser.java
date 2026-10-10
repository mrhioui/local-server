package localserver.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Incremental HTTP/1.1 request parser: a pure state machine with no I/O and no blocking.
 *
 * <p>{@link #feed} consumes whatever bytes a read produced and returns what it needs next. It can
 * be fed one byte at a time or a whole pipeline at once with the same result: every state keeps its
 * progress in fields and returns when it runs out of input. When a request completes, unconsumed
 * bytes (the next pipelined request) stay in the caller's buffer.
 *
 * <pre>
 *   HEAD -> (NONE)    ------------------------------------------> DONE
 *        -> (FIXED)   -> BODY_FIXED ------------------------------> DONE
 *        -> (CHUNKED) -> CHUNK_SIZE -> CHUNK_DATA -> CHUNK_CRLF -+
 *                            ^---------------------------------- +  (size 0) -> TRAILERS -> DONE
 * </pre>
 *
 * Any error (malformed input, refusal by the {@link HeadListener}, body over the limit) ends in
 * FAILED: framing can no longer be trusted, so the connection must answer and close.
 */
public final class HttpParser {

    public enum Status {
        /** Needs more input. */
        NEED_MORE,
        /** The head asked for "Expect: 100-continue": send the interim response, then feed again. */
        SEND_CONTINUE,
        /** A full request (head and body) is available from {@link #request()}. */
        COMPLETE,
        /** The request was refused; see {@link #error()}. */
        ERROR
    }

    private enum State { HEAD, BODY_FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_CRLF, TRAILERS, DONE, FAILED }

    private static final int MAX_CHUNK_LINE = 128;
    private static final int MAX_TRAILER_LINE = 8 * 1024;
    private static final int MAX_TRAILER_BYTES = 16 * 1024;
    private static final int MAX_CHUNK_HEX_DIGITS = 15;       // 16^15 < 2^60: cannot overflow a long

    private final HeadReader headReader;
    private final HeadListener listener;

    private State state = State.HEAD;
    private Request request;
    private BodySink sink;
    private ParseError error;

    private long limit;          // largest body this request may have
    private long remaining;      // bytes left in the fixed body, or in the current chunk
    private long received;       // decoded body bytes accepted so far
    private final StringBuilder line = new StringBuilder();   // chunk-size / trailer line being collected
    private boolean sawCr;
    private int trailerBytes;

    public HttpParser(int maxHeadBytes, HeadListener listener) {
        this.headReader = new HeadReader(maxHeadBytes);
        this.listener = listener;
    }

    public Status feed(ByteBuffer in) {
        try {
            while (true) {
                switch (state) {
                    case HEAD: {
                        if (!in.hasRemaining()) {
                            return Status.NEED_MORE;
                        }
                        HeadReader.Result r = headReader.feed(in);
                        if (r == HeadReader.Result.NEED_MORE) {
                            return Status.NEED_MORE;
                        }
                        if (r == HeadReader.Result.TOO_LARGE) {
                            return fail(400, "request head too large");
                        }
                        Status s = headComplete();
                        if (s != null) {
                            return s;
                        }
                        break;
                    }
                    case BODY_FIXED: {
                        if (!in.hasRemaining()) {
                            return Status.NEED_MORE;
                        }
                        int n = (int) Math.min(remaining, in.remaining());
                        sink.write(in, n);
                        received += n;
                        remaining -= n;
                        if (remaining == 0) {
                            state = State.DONE;
                        }
                        break;
                    }
                    case CHUNK_SIZE: {
                        Status s = readChunkSize(in);
                        if (s != null) {
                            return s;
                        }
                        break;
                    }
                    case CHUNK_DATA: {
                        if (!in.hasRemaining()) {
                            return Status.NEED_MORE;
                        }
                        int n = (int) Math.min(remaining, in.remaining());
                        sink.write(in, n);
                        received += n;
                        remaining -= n;
                        if (remaining == 0) {
                            state = State.CHUNK_CRLF;
                            sawCr = false;
                        }
                        break;
                    }
                    case CHUNK_CRLF: {
                        if (!in.hasRemaining()) {
                            return Status.NEED_MORE;
                        }
                        byte b = in.get();
                        if (b == '\r' && !sawCr) {
                            sawCr = true;
                        } else if (b == '\n' && sawCr) {
                            sawCr = false;
                            state = State.CHUNK_SIZE;
                        } else {
                            return fail(400, "chunk data must be followed by CRLF");
                        }
                        break;
                    }
                    case TRAILERS: {
                        Status s = readTrailers(in);
                        if (s != null) {
                            return s;
                        }
                        break;
                    }
                    case DONE:
                        return Status.COMPLETE;
                    case FAILED:
                    default:
                        return Status.ERROR;
                }
            }
        } catch (IOException e) {
            return fail(500, "cannot store the request body");
        }
    }

    /** The request being parsed (head available once parsed) or just completed; null before that. */
    public Request request() {
        return request;
    }

    /** Valid when {@link #feed} returned ERROR. */
    public ParseError error() {
        return error;
    }

    /** True once at least one byte of the current request has been received. */
    public boolean started() {
        return headReader.hasStarted();
    }

    /** Releases the current request's body and prepares for the next request on the connection. */
    public void reset() {
        close();
        headReader.reset();
        state = State.HEAD;
        request = null;
        error = null;
        remaining = 0;
        received = 0;
        limit = 0;
        line.setLength(0);
        sawCr = false;
        trailerBytes = 0;
    }

    /** Releases the body sink (deletes its temp file). Safe to call repeatedly. */
    public void close() {
        if (sink != null) {
            sink.close();
            sink = null;
        }
    }

    // ---- head ----------------------------------------------------------------------------------

    /** Returns a status to stop with, or null to keep parsing. */
    private Status headComplete() throws IOException {
        Request req;
        try {
            req = HeadParser.parse(headReader.bytes());
        } catch (HttpException e) {
            return fail(e.status(), e.getMessage());
        }
        request = req;

        HeadDecision decision = listener.onHead(req);
        if (decision.isRejected()) {
            return fail(decision.status(), decision.message());
        }
        limit = decision.maxBodyBytes();

        switch (req.bodyMode()) {
            case NONE:
                state = State.DONE;
                return null;
            case FIXED:
                if (req.contentLength() > limit) {
                    return fail(413, "request body too large");   // refused before reading a single byte
                }
                sink = decision.sinkFactory().create();
                req.attachBody(sink);
                remaining = req.contentLength();
                state = State.BODY_FIXED;
                break;
            case CHUNKED:
                sink = decision.sinkFactory().create();
                req.attachBody(sink);
                state = State.CHUNK_SIZE;
                break;
            default:
                return fail(500, "unknown body mode");
        }
        return req.expectContinue() ? Status.SEND_CONTINUE : null;
    }

    // ---- chunked framing ---------------------------------------------------------------------------

    private Status readChunkSize(ByteBuffer in) {
        while (in.hasRemaining()) {
            int r = lineByte(in.get(), MAX_CHUNK_LINE);
            if (r < 0) {
                return fail(400, "malformed chunk size line");
            }
            if (r == 0) {
                continue;
            }
            long size = parseChunkSize(line.toString());
            line.setLength(0);
            if (size < 0) {
                return fail(400, "invalid chunk size");
            }
            if (size == 0) {
                state = State.TRAILERS;
                trailerBytes = 0;
                return null;
            }
            if (received + size > limit) {
                return fail(413, "request body too large");       // known before the chunk data arrives
            }
            remaining = size;
            state = State.CHUNK_DATA;
            return null;
        }
        return Status.NEED_MORE;
    }

    private Status readTrailers(ByteBuffer in) {
        while (in.hasRemaining()) {
            if (++trailerBytes > MAX_TRAILER_BYTES) {
                return fail(400, "trailer section too large");
            }
            int r = lineByte(in.get(), MAX_TRAILER_LINE);
            if (r < 0) {
                return fail(400, "malformed trailer line");
            }
            if (r == 0) {
                continue;
            }
            boolean blank = line.length() == 0;
            line.setLength(0);                                   // trailer fields are read and ignored
            if (blank) {
                state = State.DONE;
                return null;
            }
        }
        return Status.NEED_MORE;
    }

    /**
     * Strict CRLF line collector. Returns 0 to keep going, 1 when a line is complete (its text is in
     * {@link #line}), -1 on a framing error (bare CR, bare LF, or a line longer than maxLen).
     */
    private int lineByte(byte b, int maxLen) {
        if (b == '\r') {
            if (sawCr) {
                return -1;
            }
            sawCr = true;
            return 0;
        }
        if (b == '\n') {
            if (!sawCr) {
                return -1;
            }
            sawCr = false;
            return 1;
        }
        if (sawCr || line.length() >= maxLen) {
            return -1;
        }
        line.append((char) (b & 0xFF));
        return 0;
    }

    /** Hex size, optionally followed by ";extension" (ignored). Returns -1 if invalid. */
    static long parseChunkSize(String text) {
        int semi = text.indexOf(';');
        int end = semi >= 0 ? semi : text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;                                               // whitespace before ';' is allowed
        }
        if (end == 0 || end > MAX_CHUNK_HEX_DIGITS) {
            return -1;
        }
        long size = 0;
        for (int i = 0; i < end; i++) {
            int d = HeadParser.hexValue(text.charAt(i));
            if (d < 0) {
                return -1;
            }
            size = size * 16 + d;
        }
        return size;
    }

    private Status fail(int status, String message) {
        error = new ParseError(status, message);
        state = State.FAILED;
        return Status.ERROR;
    }
}
