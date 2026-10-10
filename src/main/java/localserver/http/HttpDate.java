package localserver.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats the {@code Date} header ("Tue, 06 Oct 2026 10:00:00 GMT"). The value only changes once a
 * second, so it is cached. Not thread-safe: it belongs to the event loop thread.
 */
public final class HttpDate {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private long cachedSecond = -1;
    private String cached = "";

    public String now() {
        long second = System.currentTimeMillis() / 1000;
        if (second != cachedSecond) {
            cached = FORMAT.format(Instant.ofEpochSecond(second));
            cachedSecond = second;
        }
        return cached;
    }
}
