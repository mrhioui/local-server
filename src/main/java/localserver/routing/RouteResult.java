package localserver.routing;

import localserver.config.Route;

/**
 * Outcome of {@link Router#route}: the matched route, the relative path within the route root,
 * and a status hint (200 = ok, 404 = no match, 405 = method not allowed).
 *
 */
public class RouteResult {
    private final Route route;
    private final String relativePath;
    private final int statusHint;

    public RouteResult(Route route, String relativePath, int statusHint) {
        this.route        = route;
        this.relativePath = relativePath;
        this.statusHint   = statusHint;
    }

    /** Backward-compat constructor (assumes 200 when route non-null, 404 otherwise). */
    public RouteResult(Route route, String relativePath) {
        this(route, relativePath, route != null ? 200 : 404);
    }

    public Route getRoute() {
        return route;
    }

    public String getRelativePath() {
        return relativePath;
    }

    /** HTTP status hint: 200, 404, or 405. */
    public int getStatusHint() {
        return statusHint;
    }

    public boolean isMatched() {
        return route != null && statusHint == 200;
    }
}
