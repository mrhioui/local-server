package localserver.config;

/**
 * A problem that makes the whole configuration unusable (unreadable file, invalid JSON,
 * no valid server block at all). Problems confined to a single server block are NOT
 * thrown; they are reported in {@link LoadedConfig#problems()} and that block is skipped.
 */
public final class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
