package localserver.routing;

import localserver.config.Route;

/**
 * Outcome of {@link Router#route}: the matched route plus the part of the
 * request path that remains after the route prefix.
 *
 * STUB (person B): flesh out as matching logic firms up.
 */
public class RouteResult {
    private final Route route;
    private final String relativePath;

    public RouteResult(Route route, String relativePath) {
        this.route = route;
        this.relativePath = relativePath;
    }

    public Route getRoute() {
        return route;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public boolean isMatched() {
        return route != null;
    }
}
