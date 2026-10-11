package localserver;

import java.io.IOException;
import java.nio.file.Path;
import localserver.config.ConfigException;
import localserver.config.ConfigLoader;
import localserver.config.LoadedConfig;
import localserver.server.Listener;
import localserver.server.Server;
import localserver.util.Log;

/** Entry point: load the configuration, open the listeners, run the event loop until stopped. */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        Path file = Path.of(args.length > 0 ? args[0] : "config.json");
        if (System.getenv("LOCALSERVER_DEBUG") != null) {
            Log.setDebug(true);
        }

        LoadedConfig config;
        try {
            config = ConfigLoader.load(file);
        } catch (ConfigException e) {
            System.err.println("config error: " + e.getMessage());
            System.exit(1);
            return;
        }
        for (String problem : config.problems()) {
            Log.warn("config block skipped - " + problem);
        }

        Server server;
        try {
            server = Server.open(config);
        } catch (IOException e) {
            System.err.println("cannot start: " + e.getMessage());
            System.exit(1);
            return;
        }
        for (String failure : server.bindFailures()) {
            Log.warn("listener skipped - " + failure);
        }
        for (Listener l : server.listeners()) {
            Log.info("listening on " + l.spec().key() + " (" + l.spec().servers().size() + " server(s), default "
                    + l.spec().defaultServer().label() + ")");
        }

        // The hook only asks the loop to stop and waits for it; all I/O stays on the main thread.
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                mainThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown"));

        server.run();
        Log.info("stopped");
    }
}
