package localserver.handler;

import java.util.Locale;
import java.util.Map;

/**
 * Maps file extensions to MIME types.
 */
public final class Mime {

    public static final String DEFAULT_MIME = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
        // Web Documents & Text (explicit UTF-8 charset to prevent browser encoding bugs)
        Map.entry("html", "text/html; charset=utf-8"),
        Map.entry("htm",  "text/html; charset=utf-8"),
        Map.entry("css",  "text/css; charset=utf-8"),
        Map.entry("js",   "text/javascript; charset=utf-8"),
        Map.entry("mjs",  "text/javascript; charset=utf-8"),
        Map.entry("json", "application/json; charset=utf-8"),
        Map.entry("txt",  "text/plain; charset=utf-8"),
        Map.entry("xml",  "application/xml; charset=utf-8"),
        Map.entry("csv",  "text/csv; charset=utf-8"),

        // Images
        Map.entry("png",  "image/png"),
        Map.entry("jpg",  "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif",  "image/gif"),
        Map.entry("svg",  "image/svg+xml"),
        Map.entry("ico",  "image/x-icon"),
        Map.entry("webp", "image/webp"),
        Map.entry("avif", "image/avif"),
        Map.entry("bmp",  "image/bmp"),

        // Web Fonts
        Map.entry("woff",  "font/woff"),
        Map.entry("woff2", "font/woff2"),
        Map.entry("ttf",   "font/ttf"),
        Map.entry("otf",   "font/otf"),

        // Media & Binaries
        Map.entry("pdf",   "application/pdf"),
        Map.entry("wasm",  "application/wasm"),
        Map.entry("zip",   "application/zip"),
        Map.entry("gz",    "application/gzip"),
        Map.entry("tar",   "application/x-tar"),
        Map.entry("mp3",   "audio/mpeg"),
        Map.entry("wav",   "audio/wav"),
        Map.entry("mp4",   "video/mp4"),
        Map.entry("webm",  "video/webm")
    );

    private Mime() {
    }

    /** Content-Type for a file path, defaulting to application/octet-stream. */
    public static String forPath(String path) {
        if (path == null) {
            return DEFAULT_MIME;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return DEFAULT_MIME;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, DEFAULT_MIME);
    }
}
