package tracewall.data;

import java.util.*;

/** Merge bounded DNS and device-event snapshots without mutating either source. */
public final class ActivityFeed {
    private ActivityFeed() {}
    public static List<Map<String, Object>> merge(List<Map<String, Object>> history,
            List<Map<String, Object>> events, int limit) {
        if (limit < 0 || limit > 200) throw new IllegalArgumentException("Invalid feed limit");
        List<Map<String, Object>> all = new ArrayList<>();
        append(all, history);
        append(all, events);
        all.sort((a, b) -> Long.compare(((Number)b.get("time_ms")).longValue(), ((Number)a.get("time_ms")).longValue()));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }
    private static void append(List<Map<String, Object>> target, List<Map<String, Object>> source) {
        if (source == null) return;
        for (Map<String, Object> row : source) {
            if (row != null && row.get("time_ms") instanceof Number) target.add(new HashMap<>(row));
        }
    }
}
