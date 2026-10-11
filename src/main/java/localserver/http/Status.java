package localserver.http;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP status codes with their reason phrases.
 *
 * <p>Merged form: an enum (code + reason) so the response layer can carry a typed status, plus a
 * static {@link #reason(int)} lookup so lower layers that only have a numeric code (e.g. the
 * connection/error path) can still get a phrase. Not to be confused with {@code HttpParser.Status},
 * which is the parser's state machine.
 */
public enum Status {
    CONTINUE(100, "Continue"),
    OK(200, "OK"),
    CREATED(201, "Created"),
    NO_CONTENT(204, "No Content"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),
    SEE_OTHER(303, "See Other"),
    NOT_MODIFIED(304, "Not Modified"),
    TEMPORARY_REDIRECT(307, "Temporary Redirect"),
    PERMANENT_REDIRECT(308, "Permanent Redirect"),
    BAD_REQUEST(400, "Bad Request"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    CONFLICT(409, "Conflict"),
    LENGTH_REQUIRED(411, "Length Required"),
    CONTENT_TOO_LARGE(413, "Content Too Large"),
    URI_TOO_LONG(414, "URI Too Long"),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported");

    public final int code;
    public final String reason;

    Status(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    private static final Map<Integer, Status> BY_CODE = new HashMap<>();
    static {
        for (Status s : values())
            BY_CODE.put(s.code, s);
    }

    /** The Status for a numeric code, or null if unknown. */
    public static Status fromCode(int code) {
        return BY_CODE.get(code);
    }

    /** Reason phrase for a numeric code, or "Unknown" if unmapped. */
    public static String reasonFor(int code) {
        Status s = BY_CODE.get(code);
        return s != null ? s.reason : "Unknown";
    }

    /** Alias for {@link #reasonFor(int)} used by the connection/error path. */
    public static String reason(int code) {
        return reasonFor(code);
    }
}
