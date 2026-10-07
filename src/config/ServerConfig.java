package config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerConfig {
    private int port = 8080;
    private String host = "127.0.0.1";
    private String root = "www";
    private String defaultFile = "index.html";
    private long maxBodySize = 10485760; // Default 10 MB limit
    private Map<String, RouteConfig> routes = new HashMap<>();

    public static class RouteConfig {
        private String path;
        private List<String> allowedMethods;
        private String root;
        private boolean autoIndex = false;
        private String defaultFile;
        private String uploadDir;

        public RouteConfig(String path, List<String> allowedMethods, String root, boolean autoIndex, String defaultFile,
                String uploadDir) {
            this.path = path;
            this.allowedMethods = allowedMethods;
            this.root = root;
            this.autoIndex = autoIndex;
            this.defaultFile = defaultFile;
            this.uploadDir = uploadDir;
        }

        public String getPath() {
            return path;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public String getRoot() {
            return root;
        }

        public boolean isAutoIndex() {
            return autoIndex;
        }

        public String getDefaultFile() {
            return defaultFile;
        }

        public String getUploadDir() {
            return uploadDir;
        }
    }

    // --- Getters & Setters ---

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getDefaultFile() {
        return defaultFile;
    }

    public void setDefaultFile(String defaultFile) {
        this.defaultFile = defaultFile;
    }

    public long getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Map<String, RouteConfig> getRoutes() {
        return routes;
    }

    public void addRoute(String path, RouteConfig route) {
        this.routes.put(path, route);
    }
}