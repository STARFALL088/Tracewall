package tracewall.data;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import tracewall.security.PairingCode;

/** Local parent display names only. Selecting a saved UID never grants server access. */
public final class DeviceProfiles {
    public static final int MAX_DEVICES = 20;
    private static final int MAX_BYTES = 32_000;
    private final Map<String, String> names;

    public DeviceProfiles(Map<String, String> names) {
        if (names == null || names.size() > MAX_DEVICES) throw new IllegalArgumentException("Too many devices");
        Map<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (!PairingCode.validChildUid(entry.getKey()) || !validName(entry.getValue()))
                throw new IllegalArgumentException("Invalid device profile");
            copy.put(entry.getKey(), entry.getValue());
        }
        this.names = Collections.unmodifiableMap(copy);
    }

    public static boolean validName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 80) return false;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return false;
        return true;
    }

    public Map<String, String> names() { return names; }
    public DeviceProfiles put(String uid, String name) {
        Map<String, String> next = new TreeMap<>(names);
        next.put(uid, name);
        return new DeviceProfiles(next);
    }
    public DeviceProfiles remove(String uid) {
        Map<String, String> next = new TreeMap<>(names);
        next.remove(uid);
        return new DeviceProfiles(next);
    }

    public String serialize() {
        try {
            JSONArray devices = new JSONArray();
            for (Map.Entry<String, String> entry : names.entrySet())
                devices.put(new JSONObject().put("uid", entry.getKey()).put("name", entry.getValue()));
            return new JSONObject().put("version", 1).put("devices", devices).toString();
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid device profiles", e); }
    }

    public static DeviceProfiles parse(String json) {
        if (json == null) return new DeviceProfiles(Collections.emptyMap());
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Profile data too large");
        try {
            JSONTokener tokenizer = new JSONTokener(json);
            Object value = tokenizer.nextValue();
            if (!(value instanceof JSONObject) || tokenizer.nextClean() != 0) throw new IllegalArgumentException("Invalid profiles JSON");
            JSONObject root = (JSONObject) value;
            if (!keys(root).equals(new HashSet<>(java.util.Arrays.asList("version", "devices"))))
                throw new IllegalArgumentException("Invalid profile fields");
            Object version = root.get("version");
            if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 1)
                throw new IllegalArgumentException("Unknown profile version");
            JSONArray rows = root.getJSONArray("devices");
            if (rows.length() > MAX_DEVICES) throw new IllegalArgumentException("Too many devices");
            Map<String, String> names = new TreeMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!keys(row).equals(new HashSet<>(java.util.Arrays.asList("uid", "name")))
                        || !(row.get("uid") instanceof String) || !(row.get("name") instanceof String))
                    throw new IllegalArgumentException("Invalid profile row");
                String uid = row.getString("uid");
                if (names.put(uid, row.getString("name")) != null) throw new IllegalArgumentException("Duplicate device");
            }
            return new DeviceProfiles(names);
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid device profiles", e); }
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> keys = new HashSet<>();
        Iterator<String> it = object.keys();
        while (it.hasNext()) keys.add(it.next());
        return keys;
    }
}
