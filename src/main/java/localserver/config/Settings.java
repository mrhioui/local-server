package localserver.config;

/**
 * Global limits and timeouts (the optional top-level "settings" object).
 *
 * @param idleTimeoutMs      a request in progress may not stall (no bytes moving) longer than this
 * @param keepAliveTimeoutMs an idle connection between requests (or a new one that sends nothing)
 *                           is closed after this long
 * @param requestTimeoutMs   hard deadline for one request, from its first byte until its response
 *                           is fully written; this is what stops "slowloris" trickling
 * @param maxHeaderBytes     largest accepted request head (request line + headers)
 * @param maxConnections     simultaneous client connections; beyond this, new ones are closed at once
 */
public record Settings(
        int idleTimeoutMs,
        int keepAliveTimeoutMs,
        int requestTimeoutMs,
        int maxHeaderBytes,
        int maxConnections) {

    /** maxConnections stays well under the common 1024 open-files limit on purpose. */
    public static Settings defaults() {
        return new Settings(15_000, 5_000, 30_000, 16 * 1024, 512);
    }
}
