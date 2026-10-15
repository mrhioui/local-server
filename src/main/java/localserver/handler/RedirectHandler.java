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
import java.nio.charset.StandardCharsets;

public class RedirectHandler implements Handler {
    @Override
    public Response handle(Request request, Route route) {
        Route.Redirect redir = route.redirect();
        if (redir == null) {
            return ErrorPages.render(500);
        }
        Response response = new Response();
        response.setStatus(redir.code());
        response.addHeader("Location", redir.to());
        String body = "<!DOCTYPE html><html><head><title>Redirect</title></head><body>"
                + "<p>Redirecting to <a href=\"" + Html.escape(redir.to()) + "\">"
                + Html.escape(redir.to()) + "</a>.</p></body></html>";
        response.setBody(body.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        return response;
    }
}
