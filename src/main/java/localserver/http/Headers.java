package localserver.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The header fields of a request. Names are matched case-insensitively (as HTTP requires) but
 * keep the spelling the client used. A name sent several times keeps every value, in order.
 */
public final class Headers {
    private final Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private int count;

    public void add(String name, String value) {
        map.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        count++;
    }

    public boolean contains(String name) {
        return map.containsKey(name);
    }

    /** First value of the header, or null. */
    public String first(String name) {
        List<String> values = map.get(name);
        return values == null ? null : values.get(0);
    }

    public List<String> all(String name) {
        List<String> values = map.get(name);
        return values == null ? List.of() : Collections.unmodifiableList(values);
    }

    /** All values joined with ", " (the HTTP rule for repeated fields), or null. */
    public String joined(String name) {
        List<String> values = map.get(name);
        return values == null ? null : String.join(", ", values);
    }

    /** Number of header lines received (a name sent twice counts twice). */
    public int count() {
        return count;
    }

    public Map<String, List<String>> asMap() {
        return Collections.unmodifiableMap(map);
    }
}
