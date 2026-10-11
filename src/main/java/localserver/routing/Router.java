package localserver.routing;

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
        throw new UnsupportedOperationException("TODO: implement Router.route");
    }
}
