package localserver.config;

/**
 * Minimal JSON parser for the config file.
 *
 * STUB (person B): replace the ad-hoc string scanning in ConfigLoader with a
 * real recursive parser. Intended to return nested Map/List/String/Number/
 * Boolean structures.
 */
public final class Json {

    private Json() {
    }

    /**
     * Parses JSON text into a tree of Map&lt;String,Object&gt;, List&lt;Object&gt;,
     * String, Double, Boolean and null.
     */
    public static Object parse(String text) {
        throw new UnsupportedOperationException("TODO: implement Json.parse");
    }
}
