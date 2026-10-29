package tracewall.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tracewall.blocklist.DomainPattern;

/** Version 1 local rule backup. Remote rules and secrets are never imported. */
public final class RulesJson {
    public static final int MAX_BYTES = 256_000;
    public static final int MAX_ENTRIES = 1000;
    public static final Set<String> CATEGORIES = Collections.unmodifiableSet(
        new TreeSet<>(Arrays.asList("adult", "ads", "gambling", "malware")));
    public final Set<String> allow;
    public final Set<Integer> blockedUids;
    public final Map<String, Boolean> categories;

    public RulesJson(Collection<String> allow, Collection<Integer> blocked, Map<String, Boolean> categories) {
        if (allow == null || blocked == null || categories == null
                || allow.size() > MAX_ENTRIES || blocked.size() > MAX_ENTRIES
                || !categories.keySet().equals(CATEGORIES)) throw new IllegalArgumentException("Invalid rule sizes/categories");
        Set<String> normalized = new TreeSet<>();
        for (String host : allow) {
            DomainPattern pattern = DomainPattern.parse(host);
            if (pattern == null || !pattern.text().contains(".")) throw new IllegalArgumentException("Invalid domain");
            normalized.add(pattern.text());
        }
        for (Integer uid : blocked) if (uid == null || uid < 0) throw new IllegalArgumentException("Invalid UID");
        for (Boolean enabled : categories.values()) if (enabled == null) throw new IllegalArgumentException("Invalid toggle");
        this.allow = Collections.unmodifiableSet(normalized);
        this.blockedUids = Collections.unmodifiableSet(new TreeSet<>(blocked));
        this.categories = Collections.unmodifiableMap(new TreeMap<>(categories));
    }

    public String serialize() {
        try {
            JSONObject toggles = new JSONObject();
            for (Map.Entry<String, Boolean> entry : categories.entrySet()) toggles.put(entry.getKey(), entry.getValue());
            String json = new JSONObject().put("version", 1).put("allow", new JSONArray(allow))
                .put("blocked_uids", new JSONArray(blockedUids)).put("categories", toggles).toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Backup too large");
            return json;
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid rules", e); }
    }

    public static RulesJson parse(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Backup too large");
        try {
            JSONTokener tokenizer = new JSONTokener(json);
            Object value = tokenizer.nextValue();
            if (!(value instanceof JSONObject) || tokenizer.nextClean() != 0) throw new IllegalArgumentException("Invalid JSON document");
            JSONObject root = (JSONObject) value;
            requireKeys(root, new HashSet<>(Arrays.asList("version", "allow", "blocked_uids", "categories")));
            if (!integer(root.get("version")) || ((Number)root.get("version")).longValue() != 1) throw new IllegalArgumentException("Unknown version");
            JSONArray allows = root.getJSONArray("allow"), uids = root.getJSONArray("blocked_uids");
            if (allows.length() > MAX_ENTRIES || uids.length() > MAX_ENTRIES) throw new IllegalArgumentException("Too many rules");
            List<String> hosts = new ArrayList<>();
            List<Integer> blocked = new ArrayList<>();
            for (int i = 0; i < allows.length(); i++) {
                Object host = allows.get(i);
                if (!(host instanceof String)) throw new IllegalArgumentException("Domain must be a string");
                hosts.add((String)host);
            }
            for (int i = 0; i < uids.length(); i++) {
                Object uid = uids.get(i);
                if (!integer(uid) || ((Number)uid).longValue() < 0 || ((Number)uid).longValue() > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("UID must be a nonnegative integer");
                blocked.add(((Number)uid).intValue());
            }
            JSONObject toggles = root.getJSONObject("categories");
            requireKeys(toggles, CATEGORIES);
            Map<String, Boolean> categories = new TreeMap<>();
            for (String category : CATEGORIES) {
                Object enabled = toggles.get(category);
                if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("Toggle must be boolean");
                categories.put(category, (Boolean)enabled);
            }
            return new RulesJson(hosts, blocked, categories);
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid rules JSON", e); }
    }
    private static boolean integer(Object value) { return value instanceof Integer || value instanceof Long; }
    private static void requireKeys(JSONObject object, Set<String> expected) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        if (!keys.equals(expected)) throw new IllegalArgumentException("Missing or unknown fields");
    }
}
