package localserver.http;

/** Why a request was refused: the status to answer with and a short human-readable reason. */
public record ParseError(int status, String message) { }
