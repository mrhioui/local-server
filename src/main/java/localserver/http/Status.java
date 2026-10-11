package localserver.http;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP status codes with their reason phrases.
 * Replaces the old REASONS map that lived in {@link Response}.
 */
public enum Status {
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
    LENGTH_REQUIRED(411, "Length Required"),
    PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
    URI_TOO_LONG(414, "URI Too Long"),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
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
}
