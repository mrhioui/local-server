package model;

import utils.Cookie;

import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    private String method = "";
    private String uri = "";
    private String version = "HTTP/1.1";
    private Map<String, String> headers = new HashMap<>();
    private Map<String, String> queryParams = new HashMap<>();
    private Map<String, String> cookies = new HashMap<>();
    private byte[] body = new byte[0];

    // --- Getters & Setters ---

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method != null ? method.toUpperCase() : "";
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri != null ? uri : "";
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String key) {
        return headers.get(key.toLowerCase());
    }

    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            String lowerKey = key.toLowerCase().trim();
            this.headers.put(lowerKey, value.trim());

            // Automatically parse cookie map if Cookie header is added
            if ("cookie".equals(lowerKey)) {
                this.cookies = Cookie.parse(value);
            }
        }
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public String getQueryParam(String key) {
        return queryParams.get(key);
    }

    public void addQueryParam(String key, String value) {
        this.queryParams.put(key, value);
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    public String getCookie(String name) {
        return cookies.get(name);
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body != null ? body : new byte[0];
    }
}