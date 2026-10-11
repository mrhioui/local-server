package localserver.config;

import java.util.List;

/**
 * A single route block from the config (one entry of a server's "routes").
 *
 * STUB (person B): fields sketch the shape of a route; wire ConfigLoader/Json
 * to populate these and have ServerConfig hold a List&lt;Route&gt; instead of the
 * legacy RouteConfig map.
 */
public class Route {
    private String path;
    private List<String> methods;
    private String root;
    private String defaultFile;
    private boolean autoindex;
    private String uploadDir;
    private String redirect;
    private int redirectCode;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
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

    public boolean isAutoindex() {
        return autoindex;
    }

    public void setAutoindex(boolean autoindex) {
        this.autoindex = autoindex;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getRedirect() {
        return redirect;
    }

    public void setRedirect(String redirect) {
        this.redirect = redirect;
    }

    public int getRedirectCode() {
        return redirectCode;
    }

    public void setRedirectCode(int redirectCode) {
        this.redirectCode = redirectCode;
    }

    public boolean isRedirect() {
        return redirect != null && !redirect.isEmpty();
    }
}
