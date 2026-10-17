package localserver.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Headers;
import localserver.http.Request;
import org.junit.jupiter.api.Test;

class RouterTest {

    private ServerConfig createServerConfig(List<Route> routes) {
        // Sort routes longest first, as ConfigLoader does
        List<Route> sorted = routes.stream()
                .sorted((a, b) -> Integer.compare(b.path().length(), a.path().length()))
                .toList();
        return new ServerConfig(0, "127.0.0.1", List.of(8080), List.of("example.com"), true, 1048576, Map.of(), sorted);
    }

    private Request requestFor(String path) {
        return Request.of("GET", path, "example.com");
    }

    @Test
    void matchesRootRoute() {
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());
        Router router = new Router(createServerConfig(List.of(rootRoute)));

        RouteResult resRoot = router.route(requestFor("/"));
        assertTrue(resRoot.isMatched());
        assertEquals("/", resRoot.getRoute().path());
        assertEquals("", resRoot.getRelativePath());

        RouteResult resFile = router.route(requestFor("/index.html"));
        assertTrue(resFile.isMatched());
        assertEquals("index.html", resFile.getRelativePath());

        RouteResult resSub = router.route(requestFor("/sub/page.html"));
        assertTrue(resSub.isMatched());
        assertEquals("sub/page.html", resSub.getRelativePath());
    }

    @Test
    void longestPrefixWins() {
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());
        Route staticRoute = new Route("/static", Set.of("GET"), Path.of("/var/www/static"), "index.html", false, null, Map.of());
        Route cssRoute = new Route("/static/css", Set.of("GET"), Path.of("/var/www/static/css"), "index.html", false, null, Map.of());

        Router router = new Router(createServerConfig(List.of(rootRoute, staticRoute, cssRoute)));

        RouteResult res = router.route(requestFor("/static/css/main.css"));
        assertTrue(res.isMatched());
        assertEquals("/static/css", res.getRoute().path());
        assertEquals("main.css", res.getRelativePath());

        RouteResult resStatic = router.route(requestFor("/static/logo.png"));
        assertTrue(resStatic.isMatched());
        assertEquals("/static", resStatic.getRoute().path());
        assertEquals("logo.png", resStatic.getRelativePath());
    }

    @Test
    void wholeSegmentMatching() {
        Route staticRoute = new Route("/static", Set.of("GET"), Path.of("/var/www/static"), "index.html", false, null, Map.of());
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());

        Router router = new Router(createServerConfig(List.of(rootRoute, staticRoute)));

        // "/static-other" must NOT match "/static", it falls back to "/"
        RouteResult res = router.route(requestFor("/static-other"));
        assertTrue(res.isMatched());
        assertEquals("/", res.getRoute().path());
        assertEquals("static-other", res.getRelativePath());
    }

    @Test
    void noMatchWhenNoRootRoute() {
        Route apiRoute = new Route("/api", Set.of("GET"), Path.of("/var/www/api"), "index.html", false, null, Map.of());
        Router router = new Router(createServerConfig(List.of(apiRoute)));

        RouteResult res = router.route(requestFor("/other"));
        assertFalse(res.isMatched());
        assertNull(res.getRoute());
        assertEquals("", res.getRelativePath());
    }
    @Test
    void returns405WhenMethodNotAllowed() {
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());
        Router router = new Router(createServerConfig(List.of(rootRoute)));
        RouteResult res = router.route(Request.of("DELETE", "/index.html", "example.com"));
        assertFalse(res.isMatched());
        assertEquals(405, res.getStatusHint());
        assertNotNull(res.getRoute());
    }
}

