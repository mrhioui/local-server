package localserver.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON parser (RFC 8259) so the project needs no library.
 *
 * <p>Result types: objects become {@code Map<String,Object>} (insertion ordered), arrays
 * become {@code List<Object>}, strings {@code String}, integers {@code Long}, other numbers
 * {@code Double}, booleans {@code Boolean}, null {@code null}.
 *
 * <p>Strictness is deliberate for a config file: duplicate keys, trailing commas, comments,
 * leading zeros and trailing content are all errors, and every error reports line and column.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws JsonException {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // tolerate a UTF-8 byte order mark
        }
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.i < p.s.length()) {
            throw p.error("unexpected content after the JSON document");
        }
        return v;
    }

    // ---- grammar ---------------------------------------------------------------------

    private Object value(int depth) throws JsonException {
        if (i >= s.length()) {
            throw error("unexpected end of input");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth + 1);
            case '[': return array(depth + 1);
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || isDigit(c)) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw error("nesting is deeper than " + MAX_DEPTH + " levels");
        }
        i++; // '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            int keyPos = i;
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            Object v = value(depth);
            if (map.containsKey(key)) {
                i = keyPos;
                throw error("duplicate key \"" + key + "\"");
            }
            map.put(key, v);
            skipWs();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                i--;
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw error("nesting is deeper than " + MAX_DEPTH + " levels");
        }
        i++; // '['
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value(depth));
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                i--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() throws JsonException {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                i--;
                throw error("control character inside a string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw error("incomplete \\u escape");
                    }
                    int code = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = hex(s.charAt(i + k));
                        if (d < 0) {
                            i += k;
                            throw error("invalid \\u escape");
                        }
                        code = code * 16 + d;
                    }
                    i += 4;
                    sb.append((char) code);
                    break;
                default:
                    i--;
                    throw error("invalid escape sequence '\\" + e + "'");
            }
        }
    }

    private Object number() throws JsonException {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        if (peek() == '0') {
            i++;
        } else if (peek() >= '1' && peek() <= '9') {
            while (isDigit(peek())) {
                i++;
            }
        } else {
            throw error("invalid number");
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            i++;
            if (!isDigit(peek())) {
                throw error("digits expected after '.'");
            }
            while (isDigit(peek())) {
                i++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            i++;
            if (peek() == '+' || peek() == '-') {
                i++;
            }
            if (!isDigit(peek())) {
                throw error("digits expected in the exponent");
            }
            while (isDigit(peek())) {
                i++;
            }
        }
        String text = s.substring(start, i);
        try {
            return integral ? (Object) Long.parseLong(text) : (Object) Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            i = start;
            throw error("number is out of range");
        }
    }

    private void literal(String word) throws JsonException {
        if (!s.startsWith(word, i)) {
            throw error("invalid literal (expected " + word + ")");
        }
        i += word.length();
    }

    // ---- low level helpers -------------------------------------------------------------

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    /** Current character, or NUL at the end of input. */
    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() throws JsonException {
        if (i >= s.length()) {
            throw error("unexpected end of input");
        }
        return s.charAt(i++);
    }

    private void expect(char c) throws JsonException {
        if (i >= s.length() || s.charAt(i) != c) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** ASCII-only hex digit value, or -1. (Character.digit would accept non-ASCII digits.) */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private JsonException error(String message) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < i && k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException("line " + line + ", column " + col + ": " + message);
    }
}
