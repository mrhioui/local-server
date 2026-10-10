package localserver.config;

import java.util.List;
import java.util.Locale;

/**
 * One (host, port) pair to listen on, with every server that shares it. Pure data: the
 * runtime Listener (M2) wraps this with the actual ServerSocketChannel.
 *
 * @param defaultServer answers when no server name matches or the Host header is missing:
 *                      the server marked default, otherwise the first declared one
 */
public record ListenerSpec(String host, int port, List<ServerConfig> servers, ServerConfig defaultServer) {

    public ListenerSpec {
        servers = List.copyOf(servers);
    }

    public String key() {
        return host + ":" + port;
    }

    /** Virtual host selection from the raw Host header value (may be null). */
    public ServerConfig select(String hostHeader) {
        String host = normalizeHost(hostHeader);
        for (ServerConfig s : servers) {
            if (s.answersTo(host)) {
                return s;
            }
        }
        return defaultServer;
    }

    /** Lowercases, drops the port, keeps IPv6 brackets: "Site-A.test:8080" -> "site-a.test". */
    public static String normalizeHost(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }
}
