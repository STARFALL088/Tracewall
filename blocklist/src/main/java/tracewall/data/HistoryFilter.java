package tracewall.data;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

/** Immutable history selection, independent of Android and the device clock. */
public final class HistoryFilter {
    public enum Verdict { ALL, ALLOWED, BLOCKED }
    public enum Preset { TODAY, LAST_24_HOURS, LAST_7_DAYS, ALL, CUSTOM }

    // Stay below SQLite's conservative 999-variable limit, including other predicates.
    public static final int MAX_UIDS = 800;
    public final String domain;
    public final Verdict verdict;
    public final Range range;
    private final int[] uids; // null = all apps; empty = no matching installed apps

    public HistoryFilter(String domain, Verdict verdict, Range range, int[] uids) {
        if (verdict == null || range == null) throw new IllegalArgumentException("Missing selection");
        this.domain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        this.verdict = verdict;
        this.range = range;
        if (uids == null) {
            this.uids = null;
        } else {
            TreeSet<Integer> unique = new TreeSet<>();
            for (int uid : uids) {
                if (uid < -1) throw new IllegalArgumentException("Invalid UID");
                unique.add(uid);
            }
            if (unique.size() > MAX_UIDS) throw new IllegalArgumentException("Too many UIDs");
            this.uids = new int[unique.size()];
            int i = 0;
            for (int uid : unique) this.uids[i++] = uid;
        }
    }

    public int[] uids() { return uids == null ? null : uids.clone(); }

    /** Literal substring, not a LIKE pattern. Backslash is the SQL ESCAPE character. */
    public String domainLike() {
        return "%" + domain.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /** Numeric searches are exact UIDs; other input is an installed-app name/package search. */
    public static Integer exactUid(String input) {
        String text = input == null ? "" : input.trim();
        if (!text.matches("-?[0-9]+")) return null;
        int uid = Integer.parseInt(text);
        if (uid < -1) throw new IllegalArgumentException("Invalid UID");
        return uid;
    }

    /** Half-open interval: start is included, end is excluded. */
    public static final class Range {
        public final long startMs;
        public final long endMs;
        public Range(long startMs, long endMs) {
            if (startMs >= endMs) throw new IllegalArgumentException("Invalid date range");
            this.startMs = startMs;
            this.endMs = endMs;
        }
        public boolean contains(long timeMs) { return timeMs >= startMs && timeMs < endMs; }
    }

    public static Range range(Preset preset, long nowMs, TimeZone zone,
                              long customStartDay, long customEndDay) {
        if (preset == null || zone == null) throw new IllegalArgumentException("Missing range");
        switch (preset) {
            case ALL: return new Range(Long.MIN_VALUE, Long.MAX_VALUE);
            case TODAY: {
                Calendar start = day(nowMs, zone);
                Calendar end = (Calendar) start.clone();
                end.add(Calendar.DAY_OF_MONTH, 1);
                return new Range(start.getTimeInMillis(), end.getTimeInMillis());
            }
            case LAST_24_HOURS: return rolling(nowMs, 24L * 60 * 60 * 1000);
            case LAST_7_DAYS: return rolling(nowMs, 7L * 24 * 60 * 60 * 1000);
            case CUSTOM: {
                Calendar start = day(customStartDay, zone);
                Calendar end = day(customEndDay, zone);
                end.add(Calendar.DAY_OF_MONTH, 1); // Inclusive final calendar date, including DST.
                return new Range(start.getTimeInMillis(), end.getTimeInMillis());
            }
            default: throw new IllegalArgumentException("Unknown range");
        }
    }

    private static Range rolling(long nowMs, long duration) {
        long start = nowMs < Long.MIN_VALUE + duration ? Long.MIN_VALUE : nowMs - duration;
        long end = nowMs == Long.MAX_VALUE ? Long.MAX_VALUE : nowMs + 1;
        return new Range(start, end);
    }

    private static Calendar day(long timeMs, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
        calendar.setTimeInMillis(timeMs);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }
}
