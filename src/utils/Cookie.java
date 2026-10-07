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
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return cookies;
        }

        for (String pair : cookieHeader.split(";")) {
            pair = pair.trim();
            if (pair.isEmpty())
                continue;
            String[] keyValue = pair.split("=", 2);
            String name = keyValue[0].trim();
            if (name.isEmpty())
                continue;

            String value = keyValue.length == 2 ? keyValue[1].trim() : "";
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            cookies.putIfAbsent(name, value);
        }
        return cookies;
    }

    /**
     * Formats a standard HTTP "Set-Cookie" header string.
     */
    public static String formatSetCookie(String name, String value, String path, boolean httpOnly, int maxAge) {
        if (name == null || name.isEmpty() || hasIllegalChars(name) || hasIllegalChars(value)) {
            throw new IllegalArgumentException("Invalid cookie name or value");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(name).append("=").append(value == null ? "" : value);

        if (path != null && !path.isEmpty()) {
            builder.append("; Path=").append(path);
        }
        if (maxAge >= 0) {
            builder.append("; Max-Age=").append(maxAge);
        }
        if (httpOnly) {
            builder.append("; HttpOnly");
        }
        builder.append("; SameSite=Lax");

        return builder.toString();
    }

    private static boolean hasIllegalChars(String s) {
        if (s == null)
            return false;
        return s.indexOf(';') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }
}