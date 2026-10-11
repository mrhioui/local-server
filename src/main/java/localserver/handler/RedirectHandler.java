package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Emits a redirect response for routes configured with "redirect".
 * Was Router.redirect().
 *
 * STUB (person B).
 */
public class RedirectHandler implements Handler {
    @Override
    public Response handle(Request request, Route route) {
        throw new UnsupportedOperationException("TODO: implement RedirectHandler");
    }
}
