package localserver.session;

/**
 * Registry of active sessions (create / lookup / expire).
 *
 * STUB (person B): the current session store logic lives as static methods in
 * {@link Session}. The target structure splits that into an instance-based
 * SessionStore (the registry) and a Session (one session's data). Decide the
 * split, then move the storage logic here.
 */
public class SessionStore {

    /** Create a new session and return its id. */
    public String create() {
        throw new UnsupportedOperationException("TODO: implement SessionStore.create");
    }

    /** Look up a session by id, or null if unknown/expired. */
    public Session get(String id) {
        throw new UnsupportedOperationException("TODO: implement SessionStore.get");
    }

    /** Drop a session. */
    public void destroy(String id) {
        throw new UnsupportedOperationException("TODO: implement SessionStore.destroy");
    }
}
