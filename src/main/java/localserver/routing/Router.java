package localserver.routing;

import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;

/**
 * Matches an incoming request to a configured route and validates the HTTP method.
 *
 */
public class Router {
    private final ServerConfig config;

    public Router(ServerConfig config) {
        this.config = config;
    }

    public ServerConfig getConfig() {
        return config;
    }

    /**
     * Resolve the route for a request.
     *
     * <ul>
     *   <li>Returns a matched {@link RouteResult} when a route is found and the method is allowed.
     *   <li>Returns a 405 result (route non-null, method mismatch) when a route matches but the
     *       method is not allowed — callers can inspect {@link RouteResult#getStatusHint()} to
     *       distinguish 405 from 404.
     *   <li>Returns a 404 result (route null) when no route prefix matches the path.
     * </ul>
     */
    public RouteResult route(Request request) {
        String urlPath = request.path();

        // ServerConfig.matchRoute returns the longest-prefix matching route (routes are sorted
        // longest-first by ConfigLoader/ConfigValidator).
        Route matched = config.matchRoute(urlPath);

        if (matched == null) {
            // No route at all → 404
            return new RouteResult(null, urlPath, 404);
        }

        if (!matched.allows(request.method())) {
            // Route found but method not permitted → 405
            return new RouteResult(matched, urlPath, 405);
        }

        // Strip the route prefix to get the relative path within the root
        String prefix   = matched.path().equals("/") ? "" : matched.path();
        String relative = urlPath.substring(prefix.length());
        if (relative.isEmpty()) relative = "/";

        return new RouteResult(matched, relative, 200);
    }
}
