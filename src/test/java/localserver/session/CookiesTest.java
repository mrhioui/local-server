package localserver.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class CookiesTest {

    @Test
    void parsesMultipleCookies() {
        String header = "sessionId=abc1234; theme=dark; logged_in=true";
        Map<String, String> cookies = Cookies.parse(header);

        assertEquals(3, cookies.size());
        assertEquals("abc1234", cookies.get("sessionId"));
        assertEquals("dark", cookies.get("theme"));
        assertEquals("true", cookies.get("logged_in"));
    }

    @Test
    void unquotesQuotedValues() {
        String header = "data=\"quoted value\"; simple=123";
        Map<String, String> cookies = Cookies.parse(header);

        assertEquals("quoted value", cookies.get("data"));
        assertEquals("123", cookies.get("simple"));
    }

    @Test
    void handlesEmptyOrNull() {
        assertTrue(Cookies.parse(null).isEmpty());
        assertTrue(Cookies.parse("").isEmpty());
        assertTrue(Cookies.parse("   ").isEmpty());
    }

    @Test
    void formatsSetCookieHeader() {
        String formatted = Cookies.formatSetCookie("SID", "token123", "/", true, 1800);
        assertEquals("SID=token123; Path=/; Max-Age=1800; HttpOnly; SameSite=Lax", formatted);
    }

    @Test
    void rejectsInvalidCharactersInNameOrValue() {
        assertThrows(IllegalArgumentException.class, () ->
                Cookies.formatSetCookie("bad;name", "val", "/", false, 100));
        assertThrows(IllegalArgumentException.class, () ->
                Cookies.formatSetCookie("name", "val\r\nInjected: header", "/", false, 100));
    }
}
