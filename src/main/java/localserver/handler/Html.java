package localserver.handler;

import java.io.File;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * HTML helpers: escaping, autoindex directory listings, and generated error pages.
 *
 */
public final class Html {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private Html() {
    }

    /** Escape text for safe inclusion in HTML. */
    public static String escape(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    /**
     * Build an autoindex HTML page for the given directory.
     *
     * @param urlPath  The request path displayed in the title (already normalized).
     * @param dir      The real filesystem directory to list.
     */
    public static String directoryListing(String urlPath, File dir) {
        File[] entries = dir.listFiles();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\">\n")
          .append("<title>Index of ").append(escape(urlPath)).append("</title>\n</head>\n<body>\n")
          .append("<h1>Index of ").append(escape(urlPath)).append("</h1>\n<hr>\n<pre>\n");

        // Parent link (unless we are at the root)
        if (!"/".equals(urlPath)) {
            sb.append("<a href=\"../\">../</a>\n");
        }

        if (entries != null) {
            java.util.Arrays.sort(entries, (a, b) -> {
                // directories first, then alphabetical
                int dc = Boolean.compare(b.isDirectory(), a.isDirectory());
                return dc != 0 ? dc : a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : entries) {
                String name = f.getName() + (f.isDirectory() ? "/" : "");
                String link = escape(name);
                String ts   = TS.format(Instant.ofEpochMilli(f.lastModified()));
                String size = f.isDirectory() ? "-" : String.valueOf(f.length());
                sb.append(String.format("<a href=\"%s\">%-40s</a> %s %10s%n", link, link, ts, size));
            }
        }

        sb.append("</pre>\n<hr>\n</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * Build a minimal error page for the given status code.
     */
    public static String errorPage(int status, String reason) {
        return "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\">\n"
             + "<title>" + status + " " + escape(reason) + "</title>\n</head>\n<body>\n"
             + "<h1>" + status + " " + escape(reason) + "</h1>\n"
             + "</body>\n</html>\n";
    }
}
