package localserver.util;

import java.time.Instant;

/**
 * Tiny logger so the project needs no logging library. Per-connection chatter is DEBUG and off
 * by default: logging every request would dominate the cost of a stress test.
 */
public final class Log {
    private static volatile boolean quiet;
    private static volatile boolean debug;

    private Log() { }

    public static void setQuiet(boolean value) {
        quiet = value;
    }

    public static void setDebug(boolean value) {
        debug = value;
    }

    public static void info(String message) {
        if (!quiet) {
            System.out.println(Instant.now() + " INFO  " + message);
        }
    }

    public static void warn(String message) {
        if (!quiet) {
            System.err.println(Instant.now() + " WARN  " + message);
        }
    }

    public static void debug(String message) {
        if (debug && !quiet) {
            System.out.println(Instant.now() + " DEBUG " + message);
        }
    }
}
