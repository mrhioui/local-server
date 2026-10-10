package localserver.http;

import java.util.Map;

/** Reason phrases for the status codes this server can produce. */
public final class Status {
    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Continue"),
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(204, "No Content"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(303, "See Other"),
            Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(411, "Length Required"),
            Map.entry(413, "Content Too Large"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private Status() { }

    public static String reason(int code) {
        return REASONS.getOrDefault(code, "Unknown");
    }
}
