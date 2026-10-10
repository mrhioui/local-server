package localserver.http;

/**
 * The seam between parsing and routing. The parser calls it once per request, right after the head
 * is parsed and BEFORE a single body byte is read, so the owner can pick the virtual host, apply that
 * server's body limit, choose where the body goes, or refuse the request early (404, 405, 413...).
 */
@FunctionalInterface
public interface HeadListener {
    HeadDecision onHead(Request request);
}
