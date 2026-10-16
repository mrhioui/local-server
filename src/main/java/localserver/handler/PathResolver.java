package localserver.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Resolves and validates filesystem paths for a route (prevents traversal
 * outside the route root).
 */
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
            if (relativePath == null) {
                return realRoot;
            }
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            if (relativePath.isEmpty()) {
                return realRoot;
            }

            // Reject hidden/dot segments (this includes ".."), NUL bytes and backslashes
            for (String seg : relativePath.split("/")) {
                if (seg.isEmpty() || seg.equals(".")) {
                    continue;
                }
                if (seg.startsWith(".") || seg.indexOf('\0') >= 0 || seg.contains("\\")) {
                    return null;
                }
            }

            Path resolved = realRoot.resolve(relativePath).normalize();

            // Lexical containment check
            if (!resolved.startsWith(realRoot)) {
                return null;
            }

            // Symlink check: take the nearest ancestor that exists (the target itself if
            // it exists) and make sure its real path is still inside the root. This also
            // covers paths that don't exist yet (uploads / PUT), where a symlinked parent
            // directory could otherwise redirect a write outside the root.
            Path existing = resolved;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return null;
            }
            Path realExisting = existing.toRealPath(); // throws on a dangling symlink -> null
            if (!realExisting.startsWith(realRoot)) {
                return null;
            }

            return existing.equals(resolved) ? realExisting : resolved;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }
}