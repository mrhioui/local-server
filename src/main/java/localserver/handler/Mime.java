package localserver.handler;

/**
 * Maps file extensions to MIME types. Was MimeTypes.
 *
 * STUB (person B): expand the table as needed.
 */
public final class Mime {

    private Mime() {
    }

    /** Content-Type for a file path, defaulting to application/octet-stream. */
    public static String forPath(String path) {
        if (path == null)
            return "application/octet-stream";
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm"))
            return "text/html; charset=utf-8";
        if (p.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (p.endsWith(".js"))
            return "text/javascript; charset=utf-8";
        if (p.endsWith(".json"))
            return "application/json; charset=utf-8";
        if (p.endsWith(".txt"))
            return "text/plain; charset=utf-8";
        if (p.endsWith(".png"))
            return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg"))
            return "image/jpeg";
        if (p.endsWith(".gif"))
            return "image/gif";
        if (p.endsWith(".svg"))
            return "image/svg+xml";
        if (p.endsWith(".ico"))
            return "image/x-icon";
        // TODO: extend as needed
        return "application/octet-stream";
    }
}
