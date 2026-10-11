package localserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class ConfigLoader {

    /**
     * Reads JSON configuration from file path and extracts basic parameters.
     */
    public static ServerConfig load(String filePath) throws IOException {
        String jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
        ServerConfig config = new ServerConfig();

        // Extract root parameters
        String portStr = extractJsonValue(jsonContent, "port");
        if (portStr != null) {
            config.setPort(Integer.parseInt(portStr.trim()));
        }

        String hostStr = extractJsonValue(jsonContent, "host");
        if (hostStr != null) {
            config.setHost(hostStr);
        }

        String rootStr = extractJsonValue(jsonContent, "root");
        if (rootStr != null) {
            config.setRoot(rootStr);
        }

        String maxBodyStr = extractJsonValue(jsonContent, "max_body_size");
        if (maxBodyStr != null) {
            config.setMaxBodySize(Long.parseLong(maxBodyStr.trim()));
        }

        // Setup default base route
        List<String> defaultMethods = Arrays.asList("GET", "POST", "DELETE");
        ServerConfig.RouteConfig defaultRoute = new ServerConfig.RouteConfig(
                "/", defaultMethods, config.getRoot(), true, config.getDefaultFile(), "www/uploads");
        config.addRoute("/", defaultRoute);

        return config;
    }

    /**
     * Simple key extractor for basic JSON string/number key-values.
     */
    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1)
            return null;

        start += pattern.length();
        int end = json.indexOf(",", start);
        if (end == -1)
            end = json.indexOf("}", start);
        if (end == -1)
            return null;

        String rawVal = json.substring(start, end).trim();
        return rawVal.replace("\"", "");
    }
}
