package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import localserver.http.Headers;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;

public class MetricsHandlerTest {

    @Test
    void rendersHtmlDashboardByDefault() {
        MetricsHandler handler = new MetricsHandler(null);
        Request req = Request.of("GET", "/metrics", "localhost");
        Response res = handler.handle(req, null);

        assertEquals(200, res.getStatusCode());
        assertEquals("text/html; charset=utf-8", res.getHeaders().get("Content-Type"));
        String body = new String(res.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("LocalServer Metrics Dashboard"));
        assertTrue(body.contains("Single-thread Event Loop"));
    }

    @Test
    void rendersJsonWhenAcceptHeaderRequestsIt() {
        MetricsHandler handler = new MetricsHandler(null);
        Headers headers = new Headers();
        headers.add("Accept", "application/json");
        Request req = Request.of("GET", "/metrics", "localhost", headers, null);
        Response res = handler.handle(req, null);

        assertEquals(200, res.getStatusCode());
        assertEquals("application/json; charset=utf-8", res.getHeaders().get("Content-Type"));
        String body = new String(res.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"uptime_ms\":"));
        assertTrue(body.contains("\"active_sessions\":"));
    }
}

