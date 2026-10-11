package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Runs CGI scripts and relays their output.
 *
 * SHARED with person A: A owns the process/CgiJob side (see server/CgiJob),
 * B owns the Handler wiring. Coordinate before changing the contract.
 *
 * STUB.
 */
public class CgiHandler implements Handler {
    @Override
    public Response handle(Request request, Route route) {
        throw new UnsupportedOperationException("TODO: implement CgiHandler (shared with A)");
    }
}
