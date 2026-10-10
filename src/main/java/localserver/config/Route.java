package localserver.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One validated route of a server block. Immutable.
 *
 * @param path       URL prefix, normalized: starts with '/', no trailing slash except "/" itself
 * @param methods    accepted methods, in config order (so the Allow header is stable)
 * @param root       real, absolute directory the route serves from; null only for pure redirects
 * @param index      default file for directories
 * @param autoindex  whether a directory without an index file may be listed
 * @param redirect   non-null when the route is a redirect
 * @param cgi        extension (".py", lowercase) to interpreter; an empty interpreter means
 *                   "execute the file directly"
 */
public record Route(
        String path,
        Set<String> methods,
        Path root,
        String index,
        boolean autoindex,
        Redirect redirect,
        Map<String, String> cgi) {

    public record Redirect(int code, String to) { }

    public Route {
        Objects.requireNonNull(path, "path");
        methods = Collections.unmodifiableSet(new LinkedHashSet<>(methods));
        cgi = Map.copyOf(cgi);
    }

    /**
     * Prefix match on whole path segments: "/img" matches "/img" and "/img/a.png"
     * but not "/imgfoo". The route "/" matches everything.
     * Expects the already decoded and normalized URL path (no query string).
     */
    public boolean matches(String urlPath) {
        return path.equals("/") || urlPath.equals(path) || urlPath.startsWith(path + "/");
    }

    public boolean allows(String method) {
        return methods.contains(method);
    }

    /** Value for the Allow header of a 405 response. */
    public String allowHeader() {
        return String.join(", ", methods);
    }

    /**
     * Interpreter configured for this file name's extension; null if the file is not a CGI
     * script on this route. An empty string means "run the file directly".
     */
    public String interpreterFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return cgi.get(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }
}
