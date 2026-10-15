package localserver.handler;

import localserver.http.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPagesTest {

    @Test
    void render404HasCorrectStatus() {
        Response r = ErrorPages.render(404);
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void render500HasCorrectStatus() {
        Response r = ErrorPages.render(500);
        assertEquals(500, r.getStatusCode());
    }

    @Test
    void bodyContainsStatusCode() {
        Response r = ErrorPages.render(403);
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("403"), "Body should mention status code");
        assertTrue(body.contains("Forbidden"), "Body should mention reason phrase");
    }

    @Test
    void bodyIsHtml() {
        Response r = ErrorPages.render(400);
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("<html>") || body.contains("<!DOCTYPE"), "Body should be HTML");
    }

    @Test
    void noConfigRendersGenericPage() {
        Response r = ErrorPages.render(500, null);
        assertEquals(500, r.getStatusCode());
        assertNotNull(r.getBody());
        assertTrue(r.getBody().length > 0);
    }
}

