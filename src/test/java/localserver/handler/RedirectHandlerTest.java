package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;

class RedirectHandlerTest {

    @Test
    void emits301RedirectWithLocation() {
        Route.Redirect redir = new Route.Redirect(301, "/target");
        Route route = new Route("/old", Set.of("GET"), null, "index.html", false, redir, Map.of());
        Request request = Request.of("GET", "/old", "localhost");

        RedirectHandler handler = new RedirectHandler();
        Response resp = handler.handle(request, route);

        assertEquals(301, resp.getStatusCode());
        assertEquals("/target", resp.getHeaders().get("location"));
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("/target"));
    }

    @Test
    void emits302RedirectWithLocation() {
        Route.Redirect redir = new Route.Redirect(302, "https://example.com/login");
        Route route = new Route("/login", Set.of("GET"), null, "index.html", false, redir, Map.of());
        Request request = Request.of("GET", "/login", "localhost");

        RedirectHandler handler = new RedirectHandler();
        Response resp = handler.handle(request, route);

        assertEquals(302, resp.getStatusCode());
        assertEquals("https://example.com/login", resp.getHeaders().get("location"));
    }
}
