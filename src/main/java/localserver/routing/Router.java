package localserver.routing;

import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;

/**
 * Matches an incoming request to a configured route and validates the HTTP method.
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
        if (config == null || request == null) {
            return new RouteResult(null, "", 404);
        }

        String path = request.path();

        // ServerConfig.matchRoute returns the longest-prefix matching route (routes are sorted
        // longest-first by ConfigLoader/ConfigValidator).
        Route matched = config.matchRoute(path);
        if (matched == null) {
            // No route at all -> 404
            return new RouteResult(null, "", 404);
        }

        // Strip the route prefix to get the path relative to the route root
        String prefix = matched.path();
        String rel;
        if (prefix.equals("/")) {
            rel = path.startsWith("/") ? path.substring(1) : path;
        } else if (path.equals(prefix)) {
            rel = "";
        } else if (path.startsWith(prefix + "/")) {
            rel = path.substring(prefix.length() + 1);
        } else {
            rel = path.substring(prefix.length());
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
        }

        // Route found but method not permitted -> 405
        if (!matched.allows(request.method())) {
            return new RouteResult(matched, rel, 405);
        }

        return new RouteResult(matched, rel, 200);
    }
}