package tracewall.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, validated app-name metadata; no icons, network history or account secrets. */
public final class AppInventory {
    public static final int MAX_ENTRIES = 1000;
    private AppInventory() {}

    public static final class Entry {
        public final String packageName, label;
        public final int uid;
        public final long updatedMs;

        public Entry(String packageName, String label, int uid, long updatedMs) {
            if (packageName == null || packageName.length() > 255
                    || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                    || label == null || label.isEmpty() || label.length() > 200 || uid < 10000 || updatedMs < 0)
                throw new IllegalArgumentException("Invalid app metadata");
            this.packageName = packageName;
            this.label = label;
            this.uid = uid;
            this.updatedMs = updatedMs;
        }

        public Map<String, Object> record() {
            Map<String, Object> data = new HashMap<>();
            data.put("package", packageName);
            data.put("label", label);
            data.put("uid", (long) uid);
            data.put("updated_ms", updatedMs);
            return data;
        }

        public static Entry parse(Map<String, Object> data) {
            if (data == null || data.size() != 4 || !(data.get("package") instanceof String) || !(data.get("label") instanceof String))
                throw new IllegalArgumentException("Invalid app record");
            long uid = integer(data.get("uid")), updated = integer(data.get("updated_ms"));
            if (uid < 10000 || uid > Integer.MAX_VALUE) throw new IllegalArgumentException("UID out of range");
            return new Entry((String) data.get("package"), (String) data.get("label"), (int) uid, updated);
        }
    }

    public static List<Entry> parse(Collection<Map<String, Object>> rows) {
        if (rows == null || rows.size() > MAX_ENTRIES) throw new IllegalArgumentException("App inventory too large");
        List<Entry> entries = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Entry entry = Entry.parse(row);
            if (!packages.add(entry.packageName)) throw new IllegalArgumentException("Duplicate package");
            entries.add(entry);
        }
        entries.sort(Comparator.comparing((Entry e) -> e.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.packageName));
        return Collections.unmodifiableList(entries);
    }

    private static long integer(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) throw new IllegalArgumentException("Expected integer");
        return ((Number) value).longValue();
    }
}
