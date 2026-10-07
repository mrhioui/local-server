package model;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class HttpResponse {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            Locale.US);

    private static final Map<Integer, String> REASONS = Map.ofEntries(
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
            Map.entry(411, "Length Required"),
            Map.entry(413, "Payload Too Large"),
            Map.entry(414, "URI Too Long"),
            Map.entry(431, "Request Header Fields Too Large"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(504, "Gateway Timeout"),
            Map.entry(505, "HTTP Version Not Supported"));

    private int statusCode = 200;
    private String statusText = "OK";
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> setCookies = new ArrayList<>();
    private byte[] body = new byte[0];

    public HttpResponse() {
        // Standard baseline headers required for HTTP/1.1
        addHeader("Server", "LocalServer/1.0");
        addHeader("Connection", "close");
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatus(int statusCode) {
        this.statusCode = statusCode;
        this.statusText = REASONS.getOrDefault(statusCode, "Unknown");
        ;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            headers.put(clean(key), clean(value));
        }
    }

    /** One Set-Cookie line per call. */
    public void addSetCookie(String cookie) {
        if (cookie != null)
            setCookies.add(clean(cookie));
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body, String contentType) {
        this.body = body != null ? body : new byte[0];
        addHeader("Content-Length", String.valueOf(this.body.length));
        if (contentType != null)
            addHeader("Content-Type", contentType);
    }

    /**
     * Serializes headers and body payload into a unified HTTP/1.1 byte array.
     * Ready for direct transmission over a socket or NIO Channel.
     */
    public byte[] getBytes() {
        boolean noBody = statusCode == 204 || statusCode == 304
                || (statusCode >= 100 && statusCode < 200);
        byte[] payload = noBody ? new byte[0] : body;

        if (noBody)
            headers.remove("Content-Length");
        else
            headers.put("Content-Length", String.valueOf(payload.length));

        headers.putIfAbsent("Date", ZonedDateTime.now(ZoneOffset.UTC).format(HTTP_DATE));

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        for (String c : setCookies) {
            sb.append("Set-Cookie: ").append(c).append("\r\n");
        }
        sb.append("\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    /** Removes CR/LF to prevent header injection. */
    private static String clean(String s) {
        return s.replace("\r", "").replace("\n", "").trim();
    }
}