package utils;

import java.util.HashMap;
import java.util.Map;

public class Cookie {

    /**
     * Parses the raw "Cookie" request header string into key-value pairs.
     * Example input: "session_id=12345; user_theme=dark"
     */
    public static Map<String, String> parse(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
            return cookies;
        }

        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split("=", 2);
            if (keyValue.length == 2) {
                cookies.put(keyValue[0].trim(), keyValue[1].trim());
            } else if (keyValue.length == 1) {
                cookies.put(keyValue[0].trim(), "");
            }
        }
        return cookies;
    }

    /**
     * Formats a standard HTTP "Set-Cookie" header string.
     */
    public static String formatSetCookie(String name, String value, String path, boolean httpOnly, int maxAge) {
        StringBuilder builder = new StringBuilder();
        builder.append(name).append("=").append(value);

        if (path != null && !path.isEmpty()) {
            builder.append("; Path=").append(path);
        }
        if (maxAge >= 0) {
            builder.append("; Max-Age=").append(maxAge);
        }
        if (httpOnly) {
            builder.append("; HttpOnly");
        }

        return builder.toString();
    }
}