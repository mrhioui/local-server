package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Serves static files (and directory listings when autoindex is on).
 * Was GetHandler.
 *
 * STUB (person B).
 */
public class StaticHandler implements Handler {
    @Override
    public Response handle(Request request, Route route) {
        throw new UnsupportedOperationException("TODO: implement StaticHandler");
    }
}
