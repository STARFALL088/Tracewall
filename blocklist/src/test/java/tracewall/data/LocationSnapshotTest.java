package tracewall.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationSnapshotTest {
    private static Map<String, Object> valid() {
        Map<String, Object> value = new HashMap<>();
        value.put("latitude", 22.8456);
        value.put("longitude", 89.5403);
        value.put("accuracy_m", 12.5);
        value.put("time_ms", 1_000L);
        value.put("provider", "gps");
        value.put("mock", false);
        return value;
    }

    @Test void parsesExactFirestoreShape() {
        LocationSnapshot value = LocationSnapshot.parse(valid());
        assertEquals(22.8456, value.latitude);
        assertEquals(89.5403, value.longitude);
        assertEquals(12.5, value.accuracyM);
        assertEquals(1_000L, value.timeMs);
        assertEquals("gps", value.provider);
        assertFalse(value.mock);
    }

    @Test void acceptsCoordinateEdgesAndNetworkProvider() {
        assertDoesNotThrow(() -> new LocationSnapshot(-90, -180, 0, 0, "network", true));
        assertDoesNotThrow(() -> new LocationSnapshot(90, 180, LocationSnapshot.MAX_ACCURACY_M,
            Long.MAX_VALUE, "gps", false));
    }

    @Test void rejectsOutOfRangeOrNonFiniteNumbers() {
        for (double latitude : new double[] {-90.01, 90.01, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                () -> new LocationSnapshot(latitude, 0, 1, 1, "gps", false));
        for (double longitude : new double[] {-180.01, 180.01, Double.NaN, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                () -> new LocationSnapshot(0, longitude, 1, 1, "gps", false));
        for (double accuracy : new double[] {-1, LocationSnapshot.MAX_ACCURACY_M + 1, Double.NaN})
            assertThrows(IllegalArgumentException.class,
                () -> new LocationSnapshot(0, 0, accuracy, 1, "gps", false));
    }

    @Test void rejectsUnknownProvidersAndNegativeTime() {
        assertThrows(IllegalArgumentException.class,
            () -> new LocationSnapshot(0, 0, 1, 1, "passive", false));
        assertThrows(IllegalArgumentException.class,
            () -> new LocationSnapshot(0, 0, 1, -1, "gps", false));
    }

    @Test void rejectsUnknownMissingOrWrongTypedFields() {
        Map<String, Object> extra = valid(); extra.put("name", "child");
        assertThrows(IllegalArgumentException.class, () -> LocationSnapshot.parse(extra));
        Map<String, Object> missing = valid(); missing.remove("mock");
        assertThrows(IllegalArgumentException.class, () -> LocationSnapshot.parse(missing));
        Map<String, Object> floatingTime = valid(); floatingTime.put("time_ms", 1000.5);
        assertThrows(IllegalArgumentException.class, () -> LocationSnapshot.parse(floatingTime));
        Map<String, Object> stringCoordinate = valid(); stringCoordinate.put("latitude", "22.8");
        assertThrows(IllegalArgumentException.class, () -> LocationSnapshot.parse(stringCoordinate));
    }

    @Test void computesAgeAndStalenessWithoutUnderflow() {
        LocationSnapshot value = LocationSnapshot.parse(valid());
        assertEquals(0, value.ageMs(500));
        assertTrue(value.isStale(500));
        assertFalse(value.isStale(1_000 + LocationSnapshot.STALE_AFTER_MS));
        assertTrue(value.isStale(1_001 + LocationSnapshot.STALE_AFTER_MS));
    }
}
