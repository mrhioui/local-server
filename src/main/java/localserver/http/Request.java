package localserver.http;

import localserver.session.Cookies;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Request {
    private String method = "";
    private String uri = ""; // raw, as received: /a%20b?x=1
    private String path = ""; // decoded, no query: /a b
    private String queryString = ""; // x=1
    private boolean malformedUri = false;
    private String version = "HTTP/1.1";
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private Map<String, String> cookies = new HashMap<>();
    private byte[] body = new byte[0];

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method != null ? method.toUpperCase() : "";
    }

    public String getUri() {
        return uri;
    }

    public String getPath() {
        return path;
    }

    public String getQueryString() {
        return queryString;
    }

    public boolean isMalformedUri() {
        return malformedUri;
    }

    public void setUri(String uri) {
        this.uri = uri != null ? uri : "";
        this.malformedUri = false;
        this.queryParams.clear();

        int q = this.uri.indexOf('?');
        String rawPath = q >= 0 ? this.uri.substring(0, q) : this.uri;
        this.queryString = q >= 0 ? this.uri.substring(q + 1) : "";

        try {
            // '+' is a literal plus in a path, so protect it from URLDecoder
            this.path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (this.path.indexOf('\0') >= 0)
                malformedUri = true;

            if (!queryString.isEmpty()) {
                for (String pair : queryString.split("&")) {
                    if (pair.isEmpty())
                        continue;
                    String[] kv = pair.split("=", 2);
                    String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String v = kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                    queryParams.putIfAbsent(k, v);
                }
            }
        } catch (IllegalArgumentException e) {
            this.path = rawPath;
            this.malformedUri = true;
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version != null ? version : "HTTP/1.1";
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String key) {
        return key == null ? null : headers.get(key.toLowerCase());
    }

    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            String lowerKey = key.toLowerCase().trim();
            this.headers.put(lowerKey, value.trim());

            // Automatically parse cookie map if Cookie header is added
            if ("cookie".equals(lowerKey)) {
                this.cookies = Cookies.parse(value);
            }
        }
    }

    /** Host header without the port: "test.com:8080" -> "test.com" */
    public String getHost() {
        String h = getHeader("host");
        if (h == null)
            return "";
        if (h.startsWith("[")) { // IPv6 literal
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public String getQueryParam(String key) {
        return queryParams.get(key);
    }

    public void addQueryParam(String key, String value) {
        queryParams.put(key, value);
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
