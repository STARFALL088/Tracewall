package tracewall.privacy;

/** The only accepted durable-history windows; zero does not disable the live in-memory view. */
public final class HistoryRetention {
    public static final int DEFAULT_DAYS = 7;
    private static final long DAY_MS = 86_400_000L;
    private HistoryRetention() {}
    public static boolean valid(int days) { return days == 0 || days == 1 || days == 3 || days == 7; }
    public static int fromStored(int days) { return valid(days) ? days : DEFAULT_DAYS; }
    public static long cutoff(int days, long nowMs) {
        if (!valid(days) || nowMs < 0) throw new IllegalArgumentException("Invalid retention or clock");
        return Math.max(0, nowMs - days * DAY_MS);
    }
    public static boolean retain(int days, long timeMs, long nowMs) {
        return days > 0 && timeMs >= 0 && timeMs >= cutoff(days, nowMs);
    }
}
