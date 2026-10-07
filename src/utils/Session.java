package utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Session {

    public static final int TTL_SECONDS = 30 * 60; // use as Max-Age in the cookie
    private static final long TTL_MS = TTL_SECONDS * 1000L;
    private static final int MAX_SESSIONS = 10_000;

    private static class Entry {
        final Map<String, Object> data = new HashMap<>();
        long lastAccess = System.currentTimeMillis();
    }

    // accessOrder=true: least recently used entries sit at the head of the map
    private static final Map<String, Entry> SESSIONS = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Creates a new session and returns its unique token.
     */
    public static synchronized String createSession() {
        purgeExpired();

        // Hard cap: evict least recently used sessions
        while (SESSIONS.size() >= MAX_SESSIONS) {
            Iterator<String> it = SESSIONS.keySet().iterator();
            it.next();
            it.remove();
        }

        String id = UUID.randomUUID().toString();
        SESSIONS.put(id, new Entry());
        return id;
    }

    /**
     * Returns the session data, or null if unknown/expired. Refreshes the expiry.
     */

    public static synchronized Map<String, Object> getSession(String sessionId) {
        if (sessionId == null)
            return null;
        Entry e = SESSIONS.get(sessionId);
        if (e == null)
            return null;

        long now = System.currentTimeMillis();
        if (now - e.lastAccess > TTL_MS) {
            SESSIONS.remove(sessionId);
            return null;
        }
        e.lastAccess = now;
        return e.data;
    }

    /**
     * Sets an attribute inside an active session.
     */
    public static synchronized boolean setAttribute(String sessionId, String key, Object value) {
        Map<String, Object> sessionData = getSession(sessionId);
        if (sessionData == null)
            return false;
        sessionData.put(key, value);
        return true;
    }

    /**
     * Destroys an active session.
     */
    public static synchronized void destroySession(String sessionId) {
        if (sessionId != null)
            SESSIONS.remove(sessionId);
    }

    /** Number of live sessions (handy for a metrics/admin endpoint). */
    public static synchronized int count() {
        purgeExpired();
        return SESSIONS.size();
    }

    // Oldest-accessed entries are first, so stop at the first non-expired one.
    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastAccess > TTL_MS)
                it.remove();
            else
                break;
        }
    }
}