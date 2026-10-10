package localserver.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One validated server block. Immutable.
 *
 * @param index         position of the block in the config file (for diagnostics)
 * @param host          bind address ("localhost" is normalized to 127.0.0.1)
 * @param ports         every port this server listens on
 * @param serverNames   lowercase names matched against the Host header
 * @param markedDefault true when the block says "default": true
 * @param maxBodySize   client body limit in bytes
 * @param errorPages    status code to existing, readable HTML file
 * @param routes        sorted longest path first, so the first match is the best match
 */
public record ServerConfig(
        int index,
        String host,
        List<Integer> ports,
        List<String> serverNames,
        boolean markedDefault,
        long maxBodySize,
        Map<Integer, Path> errorPages,
        List<Route> routes) {

    public ServerConfig {
        ports = List.copyOf(ports);
        serverNames = List.copyOf(serverNames);
        errorPages = Map.copyOf(errorPages);
        routes = List.copyOf(routes);
    }

    /** "servers[2]": how this block is referred to in messages. */
    public String label() {
        return "servers[" + index + "]";
    }

    /** True if the (already normalized) host name is one of this server's names. */
    public boolean answersTo(String normalizedHost) {
        return serverNames.contains(normalizedHost);
    }

    /** Best matching route for a decoded, normalized URL path, or null (-> 404). */
    public Route matchRoute(String urlPath) {
        for (Route r : routes) {
            if (r.matches(urlPath)) {
                return r;
            }
        }
        return null;
    }
}
