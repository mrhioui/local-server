package localserver.handler;

import localserver.http.Response;

/**
 * Builds error responses, using configured custom error pages when present
 * and falling back to a generated page otherwise.
 *
 * STUB (person B).
 */
public final class ErrorPages {

    private ErrorPages() {
    }

    public static Response render(int statusCode) {
        throw new UnsupportedOperationException("TODO: implement ErrorPages.render");
    }
}
