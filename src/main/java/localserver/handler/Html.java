package localserver.handler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML helpers: escaping, autoindex directory listings, and generated error pages.
 */
public final class Html {

    private static final int MAX_LISTING_ENTRIES = 1000;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private static final int NAME_COLUMN_WIDTH = 40;

    private Html() {
    }

    /** Escape text for safe inclusion in HTML. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Generate default HTML error page. */
    public static String errorPage(int code, String reason) {
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head><meta charset=\"utf-8\"><title>" + code + " " + escape(reason) + "</title></head>\n"
                + "<body>\n"
                + "<center><h1>" + code + " " + escape(reason) + "</h1></center>\n"
                + "<hr><center>LocalServer</center>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /**
     * Generate an HTML directory listing (autoindex).
     *
     * @param requestPath the request path shown in the title
     * @param dir         the real filesystem directory to list
     */
    public static String directoryListing(String requestPath, Path dir) {
        String displayPath = requestPath.isEmpty() ? "/" : requestPath;
        if (!displayPath.endsWith("/")) {
            displayPath += "/";
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE html>\n")
                .append("<html>\n")
                .append("<head><meta charset=\"utf-8\"><title>Index of ")
                .append(escape(displayPath)).append("</title></head>\n")
                .append("<body>\n")
                .append("<h1>Index of ").append(escape(displayPath)).append("</h1>\n")
                .append("<hr>\n")
                .append("<pre>\n");

        // Parent link (unless we are at the root)
        if (!displayPath.equals("/")) {
            sb.append("<a href=\"../\">../</a>\n");
        }

        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue; // hide dotfiles
                }
                entries.add(p);
                if (entries.size() >= MAX_LISTING_ENTRIES) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // return what we have
        }

        // Sort: directories first, then alphabetically
        entries.sort((p1, p2) -> {
            boolean d1 = Files.isDirectory(p1);
            boolean d2 = Files.isDirectory(p2);
            if (d1 != d2) {
                return d1 ? -1 : 1;
            }
            return p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString());
        });

        for (Path p : entries) {
            String name = p.getFileName().toString();
            boolean isDir = Files.isDirectory(p);
            String displayName = isDir ? name + "/" : name;
            String href = encodePathSegment(name) + (isDir ? "/" : "");

            String ts = "-";
            String size = "-";
            try {
                ts = TS.format(Files.getLastModifiedTime(p).toInstant());
                if (!isDir) {
                    size = String.valueOf(Files.size(p));
                }
            } catch (IOException ignored) {
                // keep placeholders
            }

            // Pad outside the anchor so the link text isn't stretched with spaces
            int pad = Math.max(1, NAME_COLUMN_WIDTH - displayName.length());

            sb.append("<a href=\"").append(escape(href)).append("\">")
                    .append(escape(displayName)).append("</a>")
                    .append(" ".repeat(pad))
                    .append(ts).append(' ')
                    .append(String.format("%10s", size))
                    .append('\n');
        }

        sb.append("</pre>\n")
                .append("<hr>\n")
                .append("<address>LocalServer</address>\n")
                .append("</body>\n")
                .append("</html>\n");

        return sb.toString();
    }

    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}