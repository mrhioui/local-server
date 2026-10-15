package localserver.handler;

/**
 * HTML helpers (e.g. autoindex directory listings, generated error pages).
 *
 * NEW (person B). The structure notes this was "already written" elsewhere;
 * it was not present in the repo, so this is a placeholder stub. Replace with
 * the real implementation if one exists.
 */
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Html {

    private static final int MAX_LISTING_ENTRIES = 1000;

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
                + "<head><title>" + code + " " + escape(reason) + "</title></head>\n"
                + "<body>\n"
                + "<center><h1>" + code + " " + escape(reason) + "</h1></center>\n"
                + "<hr><center>LocalServer</center>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /** Generate HTML directory listing. */
    public static String directoryListing(String requestPath, Path dir) {
        String displayPath = requestPath.isEmpty() ? "/" : requestPath;
        if (!displayPath.endsWith("/")) {
            displayPath += "/";
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE html>\n")
                .append("<html>\n")
                .append("<head><title>Index of ").append(escape(displayPath)).append("</title></head>\n")
                .append("<body>\n")
                .append("<h1>Index of ").append(escape(displayPath)).append("</h1>\n")
                .append("<hr>\n")
                .append("<pre>\n");

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

            sb.append("<a href=\"").append(escape(href)).append("\">")
                    .append(escape(displayName)).append("</a>\n");
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
