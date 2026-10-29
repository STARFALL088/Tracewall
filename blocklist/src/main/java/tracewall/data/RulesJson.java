package tracewall.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tracewall.blocklist.DomainPattern;
import tracewall.blocklist.ScheduledProfile;
import tracewall.blocklist.TimedAllow;
import tracewall.blocklist.WeeklySchedule;

/**
 * Local-only v2 backup. The v1 constructor and import schema remain supported.
 * Expiries are absolute epoch milliseconds; schedules follow device-local time.
 * Parent rules, PINs, pairing credentials and history are never exported/imported.
 */
public final class RulesJson {
    public static final int VERSION = 2;
    public static final int MAX_BYTES = 256_000;
    public static final int MAX_ENTRIES = 1000;
    public static final int MAX_PROFILES = 8;
    public static final Set<String> CATEGORIES = ScheduledProfile.CATEGORIES;
    public final Set<String> allow;
    public final Set<Integer> blockedUids;
    public final Map<String, Boolean> categories;
    public final Set<String> deny;
    public final Map<String, Long> temporaryAllows;
    public final List<ScheduledProfile> profiles;

    public RulesJson(Collection<String> allow, Collection<Integer> blocked, Map<String, Boolean> categories) {
        this(allow, blocked, categories, Collections.emptySet(), Collections.emptyMap(), Collections.emptyList());
    }

    public RulesJson(Collection<String> allow, Collection<Integer> blocked, Map<String, Boolean> categories,
                     Collection<String> deny, Map<String, Long> temporaryAllows,
                     Collection<ScheduledProfile> profiles) {
        if (categories == null || !categories.keySet().equals(CATEGORIES)
                || temporaryAllows == null || temporaryAllows.size() > MAX_ENTRIES
                || profiles == null || profiles.size() > MAX_PROFILES)
            throw new IllegalArgumentException("Invalid rule sizes/categories");
        this.allow = normalizedPatterns(allow);
        this.deny = normalizedPatterns(deny);
        this.blockedUids = checkedUids(blocked);
        for (Boolean enabled : categories.values()) if (enabled == null) throw new IllegalArgumentException("Invalid toggle");
        this.categories = Collections.unmodifiableMap(new TreeMap<>(categories));
        Map<String, Long> timed = new TreeMap<>();
        for (Map.Entry<String, Long> entry : temporaryAllows.entrySet()) {
            String host = normalizePattern(entry.getKey());
            Long expiry = entry.getValue();
            if (expiry == null || expiry <= 0 || expiry > TimedAllow.MAX_EXPIRES_AT_MILLIS
                    || timed.containsKey(host) || this.allow.contains(host))
                throw new IllegalArgumentException("Invalid or duplicate temporary allow");
            timed.put(host, expiry);
        }
        this.temporaryAllows = Collections.unmodifiableMap(timed);
        List<ScheduledProfile> checked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int profileUids = 0;
        for (ScheduledProfile profile : profiles) {
            if (profile == null || !names.add(profile.name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Missing or duplicate profile");
            profileUids += profile.blockedUids.size();
            if (profileUids > MAX_ENTRIES) throw new IllegalArgumentException("Too many profile UID references");
            checked.add(profile);
        }
        checked.sort(Comparator.comparing(profile -> profile.name));
        this.profiles = Collections.unmodifiableList(checked);
    }

    /** Shared validation for the editor and both backup versions. ASCII/punycode only. */
    public static String normalizePattern(String host) {
        if (host == null || host.length() > 255) throw new IllegalArgumentException("Invalid domain");
        DomainPattern pattern = DomainPattern.parse(host);
        if (pattern == null) throw new IllegalArgumentException("Invalid domain");
        String body = pattern.kind() == DomainPattern.Kind.WILDCARD ? pattern.text().substring(2) : pattern.text();
        if (!body.contains(".")) throw new IllegalArgumentException("Domain needs at least two labels");
        for (String label : body.split("\\.")) {
            if (label.startsWith("-") || label.endsWith("-")) throw new IllegalArgumentException("Invalid domain label");
        }
        return pattern.text();
    }

    private static Set<String> normalizedPatterns(Collection<String> raw) {
        if (raw == null || raw.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many rules");
        Set<String> normalized = new TreeSet<>();
        for (String host : raw) normalized.add(normalizePattern(host));
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<Integer> checkedUids(Collection<Integer> raw) {
        if (raw == null || raw.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many UIDs");
        Set<Integer> checked = new TreeSet<>();
        for (Integer uid : raw) {
            if (uid == null || uid < 0) throw new IllegalArgumentException("Invalid UID");
            checked.add(uid);
        }
        return Collections.unmodifiableSet(checked);
    }

    public String serialize() {
        try {
            JSONObject toggles = new JSONObject();
            for (Map.Entry<String, Boolean> entry : categories.entrySet()) toggles.put(entry.getKey(), entry.getValue());
            JSONArray timed = new JSONArray(), scheduled = new JSONArray();
            for (Map.Entry<String, Long> entry : temporaryAllows.entrySet()) {
                timed.put(new JSONObject().put("pattern", entry.getKey()).put("expires_at_ms", entry.getValue()));
            }
            for (ScheduledProfile profile : profiles) {
                scheduled.put(new JSONObject().put("name", profile.name).put("enabled", profile.schedule.enabled)
                        .put("days", profile.schedule.days).put("start_minute", profile.schedule.startMinute)
                        .put("end_minute", profile.schedule.endMinute).put("categories", new JSONArray(profile.categories))
                        .put("blocked_uids", new JSONArray(profile.blockedUids)));
            }
            String json = new JSONObject().put("version", VERSION).put("allow", new JSONArray(allow))
                    .put("blocked_uids", new JSONArray(blockedUids)).put("categories", toggles)
                    .put("deny", new JSONArray(deny)).put("temporary_allows", timed).put("profiles", scheduled).toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Backup too large");
            return json;
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid rules", e); }
    }

    public static RulesJson parse(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Backup too large");
        // Android's org.json is more permissive than the JVM dependency. Validate
        // the same bounded JSON grammar (including duplicate keys) on both runtimes.
        BoundedRulesDocument.validate(json);
        try {
            JSONTokener tokenizer = new JSONTokener(json);
            Object value = tokenizer.nextValue();
            if (!(value instanceof JSONObject) || tokenizer.nextClean() != 0) throw new IllegalArgumentException("Invalid JSON document");
            JSONObject root = (JSONObject) value;
            long version = number(root.get("version"), 1, VERSION);
            Set<String> keys = new HashSet<>(Arrays.asList("version", "allow", "blocked_uids", "categories"));
            if (version == VERSION) keys.addAll(Arrays.asList("deny", "temporary_allows", "profiles"));
            requireKeys(root, keys);
            List<String> hosts = strings(root.getJSONArray("allow"), MAX_ENTRIES);
            List<Integer> blocked = uids(root.getJSONArray("blocked_uids"));
            JSONObject toggles = root.getJSONObject("categories");
            requireKeys(toggles, CATEGORIES);
            Map<String, Boolean> categories = new TreeMap<>();
            for (String category : CATEGORIES) categories.put(category, bool(toggles.get(category)));
            if (version == 1) return new RulesJson(hosts, blocked, categories);

            List<String> denied = strings(root.getJSONArray("deny"), MAX_ENTRIES);
            JSONArray timedJson = root.getJSONArray("temporary_allows"), profilesJson = root.getJSONArray("profiles");
            if (timedJson.length() > MAX_ENTRIES || profilesJson.length() > MAX_PROFILES)
                throw new IllegalArgumentException("Too many rules/profiles");
            Map<String, Long> timed = new TreeMap<>();
            for (int i = 0; i < timedJson.length(); i++) {
                JSONObject entry = timedJson.getJSONObject(i);
                requireKeys(entry, new HashSet<>(Arrays.asList("pattern", "expires_at_ms")));
                Object pattern = entry.get("pattern");
                if (!(pattern instanceof String)) throw new IllegalArgumentException("Domain must be a string");
                String host = normalizePattern((String) pattern);
                long expires = number(entry.get("expires_at_ms"), 1, TimedAllow.MAX_EXPIRES_AT_MILLIS);
                if (timed.put(host, expires) != null) throw new IllegalArgumentException("Duplicate temporary allow");
            }
            List<ScheduledProfile> profiles = new ArrayList<>();
            for (int i = 0; i < profilesJson.length(); i++) {
                JSONObject profile = profilesJson.getJSONObject(i);
                requireKeys(profile, new HashSet<>(Arrays.asList("name", "enabled", "days", "start_minute",
                        "end_minute", "categories", "blocked_uids")));
                Object name = profile.get("name");
                if (!(name instanceof String)) throw new IllegalArgumentException("Profile name must be a string");
                WeeklySchedule schedule = new WeeklySchedule(bool(profile.get("enabled")),
                        (int) number(profile.get("days"), 1, WeeklySchedule.ALL_DAYS),
                        (int) number(profile.get("start_minute"), 0, 24 * 60 - 1),
                        (int) number(profile.get("end_minute"), 0, 24 * 60 - 1));
                profiles.add(new ScheduledProfile((String) name, schedule,
                        strings(profile.getJSONArray("categories"), CATEGORIES.size()), uids(profile.getJSONArray("blocked_uids"))));
            }
            return new RulesJson(hosts, blocked, categories, denied, timed, profiles);
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid rules JSON", e); }
    }

    private static List<String> strings(JSONArray array, int max) throws JSONException {
        if (array.length() > max) throw new IllegalArgumentException("Too many entries");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String)) throw new IllegalArgumentException("Expected string");
            values.add((String) value);
        }
        return values;
    }

    private static List<Integer> uids(JSONArray array) throws JSONException {
        if (array.length() > MAX_ENTRIES) throw new IllegalArgumentException("Too many UIDs");
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add((int) number(array.get(i), 0, Integer.MAX_VALUE));
        return values;
    }

    private static long number(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < min
                || ((Number) value).longValue() > max) throw new IllegalArgumentException("Integer out of range");
        return ((Number) value).longValue();
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Toggle must be boolean");
        return (Boolean) value;
    }

    private static void requireKeys(JSONObject object, Set<String> expected) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        if (!keys.equals(expected)) throw new IllegalArgumentException("Missing or unknown fields");
    }
}
