package tracewall.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tracewall.blocklist.DomainPattern;

/** Applies one intent to the transaction's latest value, never a stale UI snapshot. */
public final class RuleEdits {
    private RuleEdits() {}

    public static String domain(String raw) {
        DomainPattern pattern = DomainPattern.parse(raw);
        if (pattern == null || !pattern.text().contains(".")) throw new IllegalArgumentException("Invalid domain pattern");
        return pattern.text();
    }

    public static Map<String, Object> allow(Object current, String host, boolean add) {
        String normalized = domain(host);
        Set<String> next = new TreeSet<>(RemoteRules.parse(single("allow", current)).allow);
        if (add) next.add(normalized); else next.remove(normalized);
        if (next.size() > RulesJson.MAX_ENTRIES) throw new IllegalArgumentException("Too many rules");
        Map<String, Object> result = new LinkedHashMap<>();
        int index = 0;
        for (String value : next) result.put(Integer.toString(index++), value);
        return result;
    }

    public static Map<String, Object> blocked(Object current, int uid, boolean add) {
        if (uid < 0) throw new IllegalArgumentException("Invalid UID");
        Set<Integer> next = new TreeSet<>();
        for (String value : RemoteRules.parse(single("blocked_uids", current)).blockedUids)
            next.add(Integer.parseInt(value));
        if (add) next.add(uid); else next.remove(uid);
        if (next.size() > RulesJson.MAX_ENTRIES) throw new IllegalArgumentException("Too many rules");
        Map<String, Object> result = new LinkedHashMap<>();
        int index = 0;
        for (int value : next) result.put(Integer.toString(index++), (long) value);
        return result;
    }

    public static Map<String, Object> category(Object current, String category, boolean enabled) {
        if (!RulesJson.CATEGORIES.contains(category)) throw new IllegalArgumentException("Invalid category");
        Set<String> selected = current == null ? RulesJson.CATEGORIES
            : RemoteRules.parse(single("categories", current)).categories;
        Map<String, Object> next = new LinkedHashMap<>();
        for (String name : RulesJson.CATEGORIES) next.put(name, selected.contains(name));
        next.put(category, enabled);
        return next;
    }

    private static Map<String, Object> single(String field, Object value) {
        return value == null ? Collections.emptyMap() : Collections.singletonMap(field, value);
    }
}
