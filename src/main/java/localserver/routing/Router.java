package localserver.routing;

import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;

/**
 * Matches an incoming request to a configured route.
 *
 * STUB (person B): implement longest-prefix path matching against the server's
 * routes and return a {@link RouteResult} (or a not-found result).
 */
public class Router {
    private final ServerConfig config;

    public Router(ServerConfig config) {
        this.config = config;
    }

    public ServerConfig getConfig() {
        return config;
    }

    /** Resolve the route for a request. */
    public RouteResult route(Request request) {
        if (config == null || request == null) {
            return new RouteResult(null, "");
        }
        String path = request.path();
        Route matched = config.matchRoute(path);
        if (matched == null) {
            return new RouteResult(null, "");
        }
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
        return new RouteResult(matched, rel);
    }
}
