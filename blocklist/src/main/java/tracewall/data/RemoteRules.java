package tracewall.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tracewall.blocklist.DomainPattern;

/** Validated immutable remote state. A missing snapshot is a replacement, not a no-op. */
public final class RemoteRules {
    public final Set<String> allow;
    public final Set<String> blockedUids;
    /** Null restores the local category selection; empty explicitly disables categories. */
    public final Set<String> categories;

    private RemoteRules(Set<String> allow, Set<String> blocked, Set<String> categories) {
        this.allow = Collections.unmodifiableSet(allow);
        this.blockedUids = Collections.unmodifiableSet(blocked);
        this.categories = categories == null ? null : Collections.unmodifiableSet(categories);
    }

    public static RemoteRules parse(Object raw) {
        Map<?, ?> root;
        if (raw == null) root = Collections.emptyMap();
        else if (raw instanceof Map) root = (Map<?, ?>) raw;
        else throw new IllegalArgumentException("Rules must be an object");
        Set<String> allow = new HashSet<>(), blocked = new HashSet<>();
        for (Object value : values(root.get("allow"))) {
            if (value == null) continue; // Firebase lists can contain deleted indices.
            if (!(value instanceof String)) throw new IllegalArgumentException("Domain must be text");
            DomainPattern pattern = DomainPattern.parse((String) value);
            if (pattern == null || !pattern.text().contains(".")) throw new IllegalArgumentException("Invalid domain");
            allow.add(pattern.text());
        }
        for (Object value : values(root.get("blocked_uids"))) {
            if (value == null) continue;
            if (!(value instanceof Long || value instanceof Integer)
                    || ((Number) value).longValue() < 0 || ((Number) value).longValue() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid app UID");
            blocked.add(Long.toString(((Number) value).longValue()));
        }
        Set<String> categories = null;
        Object toggles = root.get("categories");
        if (toggles != null) {
            if (!(toggles instanceof Map)) throw new IllegalArgumentException("Invalid categories");
            categories = new HashSet<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) toggles).entrySet()) {
                if (!RulesJson.CATEGORIES.contains(entry.getKey()) || !(entry.getValue() instanceof Boolean))
                    throw new IllegalArgumentException("Invalid category toggle");
                if (Boolean.TRUE.equals(entry.getValue())) categories.add((String) entry.getKey());
            }
        }
        return new RemoteRules(allow, blocked, categories);
    }

    private static Collection<?> values(Object raw) {
        Collection<?> values;
        if (raw == null) return Collections.emptyList();
        if (raw instanceof Map) values = ((Map<?, ?>) raw).values();
        else if (raw instanceof Collection) values = (Collection<?>) raw;
        else throw new IllegalArgumentException("Rules must be a list or indexed object");
        if (values.size() > RulesJson.MAX_ENTRIES) throw new IllegalArgumentException("Too many remote rules");
        return values;
    }
}
