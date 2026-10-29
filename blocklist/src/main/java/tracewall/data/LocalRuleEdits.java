package tracewall.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tracewall.blocklist.ScheduledProfile;
import tracewall.blocklist.TimedAllow;

/** Pure local editor intents; build a fully validated replacement before writing anything. */
public final class LocalRuleEdits {
    private LocalRuleEdits() {}

    public static RulesJson permanentAllow(RulesJson current, String raw) {
        String pattern = RulesJson.normalizePattern(raw);
        Set<String> allow = new TreeSet<>(current.allow);
        Map<String, Long> timed = new TreeMap<>(current.temporaryAllows);
        allow.add(pattern);
        timed.remove(pattern);
        return copy(current, allow, current.deny, timed, current.profiles);
    }

    /** An identical permanent exception is replaced, not accidentally left permanent. */
    public static RulesJson temporaryAllow(RulesJson current, String raw, int minutes, long nowMillis) {
        String pattern = RulesJson.normalizePattern(raw);
        long expiry = TimedAllow.expiresAfterMinutes(nowMillis, minutes);
        Set<String> allow = new TreeSet<>(current.allow);
        Map<String, Long> timed = new TreeMap<>(current.temporaryAllows);
        allow.remove(pattern);
        timed.put(pattern, expiry);
        return copy(current, allow, current.deny, timed, current.profiles);
    }

    public static RulesJson removeAllow(RulesJson current, String raw, boolean temporary) {
        String pattern = RulesJson.normalizePattern(raw);
        Set<String> allow = new TreeSet<>(current.allow);
        Map<String, Long> timed = new TreeMap<>(current.temporaryAllows);
        if (temporary) timed.remove(pattern); else allow.remove(pattern);
        return copy(current, allow, current.deny, timed, current.profiles);
    }

    public static RulesJson deny(RulesJson current, String raw, boolean blocked) {
        Set<String> deny = new TreeSet<>(current.deny);
        String pattern = RulesJson.normalizePattern(raw);
        if (blocked) deny.add(pattern); else deny.remove(pattern);
        return copy(current, current.allow, deny, current.temporaryAllows, current.profiles);
    }

    /** originalName=null adds; updated=null deletes. A stale editor must not resurrect a deleted profile. */
    public static RulesJson profile(RulesJson current, String originalName, ScheduledProfile updated) {
        List<ScheduledProfile> profiles = new ArrayList<>(current.profiles);
        if (originalName != null && !profiles.removeIf(profile -> profile.name.equals(originalName)))
            throw new IllegalArgumentException("Profile changed or removed; reopen the editor");
        if (updated != null) profiles.add(updated);
        return copy(current, current.allow, current.deny, current.temporaryAllows, profiles);
    }

    /** Advanced UID field: ASCII numbers separated by whitespace/commas, never a package lookup. */
    public static Set<Integer> parseUidInput(String input) {
        if (input == null || input.length() > 12_000) throw new IllegalArgumentException("UID field too long");
        Set<Integer> uids = new TreeSet<>();
        if (input.trim().isEmpty()) return uids;
        String[] tokens = input.trim().split("[\\s,]+", -1);
        if (tokens.length > RulesJson.MAX_ENTRIES) throw new IllegalArgumentException("Too many UIDs");
        for (String token : tokens) {
            if (token.isEmpty() || token.length() > 10 || !token.matches("[0-9]+"))
                throw new IllegalArgumentException("Invalid UID");
            try { uids.add(Integer.parseInt(token)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("UID out of range", e); }
        }
        return uids;
    }

    private static RulesJson copy(RulesJson current, Set<String> allow, Set<String> deny,
                                  Map<String, Long> timed, List<ScheduledProfile> profiles) {
        return new RulesJson(allow, current.blockedUids, current.categories, deny, timed, profiles);
    }
}
