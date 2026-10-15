package localserver.handler;

import java.nio.file.Path;

/**
 * Resolves and validates filesystem paths for a route (prevents traversal
 * outside the route root).
 *
 */
public final class PathResolver {

    private PathResolver() {
    }

    /**
     * Resolve {@code relativePath} against {@code root}, returning a safe absolute path string,
     * or {@code null} if the result would escape the root (path traversal attempt).
     *
     * @param root         absolute root directory (e.g. "/var/www")
     * @param relativePath URL path segment after the route prefix (may start with '/')
     * @return absolute path string, or null on traversal / bad input
     */
    public static String resolve(String root, String relativePath) {
        if (root == null || relativePath == null) return null;
        try {
            Path base     = Path.of(root).normalize().toAbsolutePath();
            // strip leading slash so Path.resolve doesn't treat it as absolute
            String rel    = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            Path resolved = base.resolve(rel).normalize().toAbsolutePath();

            // Traversal check: resolved must start with base
            if (!resolved.startsWith(base)) {
                return null;
            }
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Same as {@link #resolve(String, String)} but accepting {@link Path} roots
     * (convenience overload for callers that already have a Path).
     */
    public static String resolve(Path root, String relativePath) {
        return root == null ? null : resolve(root.toAbsolutePath().toString(), relativePath);
    }
}
