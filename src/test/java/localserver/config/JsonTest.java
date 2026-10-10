package localserver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void parsesObjectsArraysAndPrimitives() throws Exception {
        Object v = Json.parse("""
                {"a": [1, 2.5, -3, 1e2], "b": {"c": true, "d": false, "e": null}, "f": "x"}
                """);
        Map<?, ?> m = assertInstanceOf(Map.class, v);
        List<?> a = assertInstanceOf(List.class, m.get("a"));
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(-3L, a.get(2));
        assertEquals(100.0, a.get(3));
        Map<?, ?> b = assertInstanceOf(Map.class, m.get("b"));
        assertEquals(Boolean.TRUE, b.get("c"));
        assertEquals(Boolean.FALSE, b.get("d"));
        assertNull(b.get("e"));
        assertTrue(b.containsKey("e"));
        assertEquals("x", m.get("f"));
    }

    @Test
    void keepsKeyOrder() throws Exception {
        Map<?, ?> m = (Map<?, ?>) Json.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), List.copyOf(m.keySet()));
    }

    @Test
    void decodesStringEscapes() throws Exception {
        assertEquals("a\"b\\c/d\n\t\u00e9", Json.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00e9\""));
        assertEquals("\u0627\u0644", Json.parse("\"\\u0627\\u0644\"")); // Arabic letters survive
    }

    @Test
    void acceptsByteOrderMarkAndSurroundingWhitespace() throws Exception {
        assertEquals(5L, Json.parse("\uFEFF  \n 5 \r\n"));
    }

    @Test
    void emptyContainersAreFine() throws Exception {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[ ]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                       // nothing
            "{",                      // unterminated
            "{\"a\": 1,}",            // trailing comma (object)
            "[1, 2,]",                // trailing comma (array)
            "{\"a\": 1, \"a\": 2}",   // duplicate key
            "{} extra",               // trailing content
            "{'a': 1}",               // single quotes
            "{\"a\": 01}",            // leading zero
            "{\"a\": 1.}",            // digits expected after '.'
            "{\"a\": .5}",            // no leading digit
            "{\"a\": +1}",            // plus sign
            "[1 2]",                  // missing comma
            "{\"a\" 1}",              // missing colon
            "\"unterminated",         // unterminated string
            "\"bad \\q escape\"",     // invalid escape
            "\"bad \\u12G4 escape\"", // invalid unicode escape
            "\"tab\there\"",          // raw control character inside a string
            "// comment\n{}",         // comments are not JSON
            "tru",                    // truncated literal
            "nul",                    // truncated literal
            "[1e]",                   // exponent without digits
            "99999999999999999999"    // does not fit in a long
    })
    void rejectsMalformedDocuments(String text) {
        assertThrows(JsonException.class, () -> Json.parse(text));
    }

    @Test
    void errorMessagesCarryLineAndColumn() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n  \"a\": ]\n}"));
        assertTrue(e.getMessage().startsWith("line 2, column 8"), e.getMessage());
    }

    @Test
    void duplicateKeyIsReportedAtTheSecondKey() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n\"a\": 1,\n\"a\": 2\n}"));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        assertTrue(e.getMessage().contains("duplicate key \"a\""), e.getMessage());
    }

    @Test
    void deepNestingIsRejectedInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void nestingUpToTheLimitIsAccepted() throws Exception {
        String ok = "[".repeat(64) + "]".repeat(64);
        assertInstanceOf(List.class, Json.parse(ok));
    }
}
