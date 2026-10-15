package localserver.handler;

/**
 * Resolves and validates filesystem paths for a route (prevents traversal
 * outside the route root).
 *
 * NEW (person B). The structure notes this was "already written" elsewhere;
 * it was not present in the repo, so this is a placeholder stub. Replace with
 * the real implementation if one exists.
 */
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathResolver {

    private PathResolver() {
    }

    /**
     * Resolve a request path against a route root directory, returning a safe Path,
     * or null if it would escape the root, contains hidden/dot segments, or is invalid.
     */
    public static Path resolve(Path root, String relativePath) {
        if (root == null) {
            return null;
        }
        try {
            Path realRoot = root.toRealPath();
            if (relativePath == null || relativePath.isEmpty()) {
                return realRoot;
            }
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            if (relativePath.isEmpty()) {
                return realRoot;
            }

            // Check for hidden/dot segments or illegal characters
            String[] segments = relativePath.split("/");
            for (String seg : segments) {
                if (seg.isEmpty() || seg.equals(".")) {
                    continue;
                }
                if (seg.startsWith(".") || seg.indexOf('\0') >= 0 || seg.contains("\\")) {
                    return null;
                }
            }

            Path resolved = realRoot.resolve(relativePath).normalize();

            // Containment check
            if (!resolved.startsWith(realRoot)) {
                return null;
            }

            // If target exists, check real path to avoid symlink traversal out of root
            if (Files.exists(resolved)) {
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(realRoot)) {
                    return null;
                }
                return realResolved;
            }

            return resolved;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Resolve a request path against a route root, returning a safe absolute
     * path, or null if it would escape the root.
     */
    public static String resolve(String root, String relativePath) {
        if (root == null) {
            return null;
        }
        Path resolved = resolve(Path.of(root), relativePath);
        return resolved != null ? resolved.toString() : null;
    }
}
