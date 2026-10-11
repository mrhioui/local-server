package localserver.config;

import java.util.List;

/**
 * Result of loading a configuration.
 *
 * @param servers   the valid server blocks, in file order
 * @param listeners one entry per distinct (host, port), in first-seen order
 * @param problems  one message per skipped server block; empty when the file was fully valid
 * @param settings  global limits and timeouts (defaults when the file has no "settings")
 */
public record LoadedConfig(
        List<ServerConfig> servers,
        List<ListenerSpec> listeners,
        List<String> problems,
        Settings settings) {

    public LoadedConfig {
        servers = List.copyOf(servers);
        listeners = List.copyOf(listeners);
        problems = List.copyOf(problems);
    }
}
