package utils;

import java.util.HashMap;
import java.util.Map;

public class Session {

    // Thread-safe session store: SessionID -> (AttributeKey -> AttributeValue)
    private static final Map<String, Map<String, Object>> SESSIONS = new HashMap<>();

    /**
     * Creates a new session and returns its unique token.
     */
    public static synchronized String createSession() {
        String sessionId = java.util.UUID.randomUUID().toString();
        SESSIONS.put(sessionId, new HashMap<>());
        return sessionId;
    }

    /**
     * Retrieves session data for a given session ID, or null if invalid/expired.
     */
    public static synchronized Map<String, Object> getSession(String sessionId) {
        if (sessionId == null)
            return null;
        return SESSIONS.get(sessionId);
    }

    /**
     * Sets an attribute inside an active session.
     */
    public static synchronized void setAttribute(String sessionId, String key, Object value) {
        Map<String, Object> sessionData = getSession(sessionId);
        if (sessionData != null) {
            sessionData.put(key, value);
        }
    }

    /**
     * Destroys an active session.
     */
    public static synchronized void destroySession(String sessionId) {
        if (sessionId != null) {
            SESSIONS.remove(sessionId);
        }
    }
}