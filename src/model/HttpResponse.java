package model;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private int statusCode = 200;
    private String statusText = "OK";
    private Map<String, String> headers = new HashMap<>();
    private byte[] body = new byte[0];

    public HttpResponse() {
        // Standard baseline headers required for HTTP/1.1
        addHeader("Server", "LocalServer/1.0");
        addHeader("Connection", "close");
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatus(int statusCode, String statusText) {
        this.statusCode = statusCode;
        this.statusText = statusText;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            this.headers.put(key.trim(), value.trim());
        }
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body, String contentType) {
        this.body = body != null ? body : new byte[0];
        addHeader("Content-Length", String.valueOf(this.body.length));
        if (contentType != null) {
            addHeader("Content-Type", contentType);
        }
    }

    /**
     * Serializes headers and body payload into a unified HTTP/1.1 byte array.
     * Ready for direct transmission over a socket or NIO Channel.
     */
    public byte[] getBytes() {
        addHeader("Content-Length", String.valueOf(body.length));

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] fullResponse = new byte[headerBytes.length + body.length];

        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(body, 0, fullResponse, headerBytes.length, body.length);

        return fullResponse;
    }
}