package tracewall.blocklist;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides one lookup, in a fixed precedence order:
 *
 *   1. explicit allow (exact or wildcard)  -> wins over everything below
 *   2. per-app block (whole uid)           -> beats categories
 *   3. category blocklist                  -> beats the default
 *   4. default allow
 *
 * The allowlist has to win, or a parent cannot unbreak one host without
 * disabling a whole category - which is the first thing anyone tries to do.
 * Precedence lives here, in pure Java, because it is the part a viva asks
 * about and the part that must be provably deterministic: categories are held
 * in a TreeMap so that when a host appears in two of them, the reported reason
 * is the same on every phone.
 *
 * Matching is label-aligned through {@link DomainSet}; nothing here does
 * substring matching, so "evil-example.com" never matches "*.example.com".
 */
public final class PolicyEngine {

    public enum Verdict {
        ALLOW_DEFAULT,
        ALLOW_LISTED,
        BLOCK_APP,
        BLOCK_CATEGORY
    }

    /** The outcome for one query. Immutable, cheap, safe to log. */
    public static final class Decision {
        public final Verdict verdict;
        public final String reason;

        Decision(Verdict verdict, String reason) {
            this.verdict = verdict;
            this.reason = reason;
        }

        public boolean blocked() {
            return verdict == Verdict.BLOCK_APP || verdict == Verdict.BLOCK_CATEGORY;
        }

        @Override public String toString() {
            return reason.isEmpty() ? verdict.name() : verdict.name() + ":" + reason;
        }
    }

    private static final Decision DEFAULT_ALLOW = new Decision(Verdict.ALLOW_DEFAULT, "");
    private static final Decision ALLOWLISTED = new Decision(Verdict.ALLOW_LISTED, "allowlist");
    private static final Decision APP_BLOCKED = new Decision(Verdict.BLOCK_APP, "app");

    private final DomainSet allowList;
    private final Map<String, DomainSet> categories;   // sorted, for stable reasons
    private final Set<Integer> blockedApps;

    public PolicyEngine(DomainSet allowList,
                        Map<String, DomainSet> categories,
                        Set<Integer> blockedApps) {
        this.allowList = allowList == null ? new DomainSet() : allowList;
        this.categories = categories == null
                ? Collections.<String, DomainSet>emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(categories));
        this.blockedApps = blockedApps == null
                ? Collections.<Integer>emptySet()
                : blockedApps;
    }

    public Decision decide(String host, int uid) {
        if (host == null) return DEFAULT_ALLOW;          // caller drops it anyway
        if (allowList.matches(host)) return ALLOWLISTED;
        if (blockedApps.contains(uid)) return APP_BLOCKED;
        for (Map.Entry<String, DomainSet> e : categories.entrySet()) {
            if (e.getValue().matches(host)) {
                return new Decision(Verdict.BLOCK_CATEGORY, e.getKey());
            }
        }
        return DEFAULT_ALLOW;
    }

    /** Rules loaded, for the header line. Not a performance figure. */
    public int size() {
        int n = allowList.size() + blockedApps.size();
        for (DomainSet s : categories.values()) n += s.size();
        return n;
    }

    public int categoryCount() { return categories.size(); }

    public int allowCount() { return allowList.size(); }

    public int blockedAppCount() { return blockedApps.size(); }

    public String summary() {
        return categories.size() + " categories / " + size() + " rules / "
                + allowList.size() + " allowed / " + blockedApps.size() + " blocked apps";
    }
}
