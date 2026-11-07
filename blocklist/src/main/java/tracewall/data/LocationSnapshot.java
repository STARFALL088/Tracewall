package tracewall.data;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict latest-location document shared by a child with verified parents. */
public final class LocationSnapshot {
    public static final long STALE_AFTER_MS = 5 * 60_000L;
    public static final double MAX_ACCURACY_M = 100_000.0;
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "latitude", "longitude", "accuracy_m", "time_ms", "provider", "mock"));

    public final double latitude;
    public final double longitude;
    public final double accuracyM;
    public final long timeMs;
    public final String provider;
    public final boolean mock;

    public LocationSnapshot(double latitude, double longitude, double accuracyM,
            long timeMs, String provider, boolean mock) {
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0
                || !Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0
                || !Double.isFinite(accuracyM) || accuracyM < 0.0 || accuracyM > MAX_ACCURACY_M
                || timeMs < 0 || !("gps".equals(provider) || "network".equals(provider))) {
            throw new IllegalArgumentException("Invalid location snapshot");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        this.timeMs = timeMs;
        this.provider = provider;
        this.mock = mock;
    }

    /** Reject unknown fields and lossy timestamp conversions from untrusted cloud data. */
    public static LocationSnapshot parse(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(KEYS)
                || !(value.get("latitude") instanceof Number)
                || !(value.get("longitude") instanceof Number)
                || !(value.get("accuracy_m") instanceof Number)
                || !(value.get("time_ms") instanceof Integer || value.get("time_ms") instanceof Long)
                || !(value.get("provider") instanceof String)
                || !(value.get("mock") instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid location fields");
        }
        return new LocationSnapshot(
            ((Number) value.get("latitude")).doubleValue(),
            ((Number) value.get("longitude")).doubleValue(),
            ((Number) value.get("accuracy_m")).doubleValue(),
            ((Number) value.get("time_ms")).longValue(),
            (String) value.get("provider"),
            (Boolean) value.get("mock"));
    }

    public long ageMs(long nowMs) { return Math.max(0L, nowMs - timeMs); }
    public boolean isStale(long nowMs) { return nowMs < timeMs || ageMs(nowMs) > STALE_AFTER_MS; }
}
