package localserver.handler;

/**
 * Resolves and validates filesystem paths for a route (prevents traversal
 * outside the route root).
 *
 * NEW (person B). The structure notes this was "already written" elsewhere;
 * it was not present in the repo, so this is a placeholder stub. Replace with
 * the real implementation if one exists.
 */
public final class PathResolver {

    private PathResolver() {
    }

    /**
     * Resolve a request path against a route root, returning a safe absolute
     * path, or null if it would escape the root.
     */
    public static String resolve(String root, String relativePath) {
        throw new UnsupportedOperationException("TODO: implement PathResolver.resolve");
    }
}
