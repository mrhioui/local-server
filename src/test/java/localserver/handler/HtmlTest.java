package localserver.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTest {

    @Test
    void escapeNull() {
        assertEquals("", Html.escape(null));
    }

    @Test
    void escapeAmpersand() {
        assertEquals("&amp;", Html.escape("&"));
    }

    @Test
    void escapeLtGt() {
        assertEquals("&lt;script&gt;", Html.escape("<script>"));
    }

    @Test
    void escapeQuotes() {
        assertEquals("&quot;it&#x27;s&quot;", Html.escape("\"it's\""));
    }

    @Test
    void noSpecialCharsUnchanged() {
        assertEquals("Hello World", Html.escape("Hello World"));
    }

    @Test
    void errorPageContainsStatus() {
        String page = Html.errorPage(404, "Not Found");
        assertTrue(page.contains("404"));
        assertTrue(page.contains("Not Found"));
    }
}

