package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * A unit that turns a request (against a matched route) into a response.
 */
public interface Handler {
    Response handle(Request request, Route route);
}
