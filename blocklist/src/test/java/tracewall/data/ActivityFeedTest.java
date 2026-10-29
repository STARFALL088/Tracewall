package tracewall.data;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ActivityFeedTest {
    @Test void sortsEventsAndDnsTogetherAndCapsTheResult() {
        Map<String, Object> dns = new HashMap<>(Map.of("host", "a.test", "time_ms", 10L));
        List<Map<String, Object>> merged = ActivityFeed.merge(List.of(dns), List.of(
            Map.of("type", "new_app", "time_ms", 20L), Map.of("type", "protection_off", "time_ms", 5L)), 2);
        assertEquals("new_app", merged.get(0).get("type"));
        assertEquals("a.test", merged.get(1).get("host"));
        merged.get(1).put("host", "changed");
        assertEquals("a.test", dns.get("host"));
    }
    @Test void handlesMissingSnapshotsAndBadTimesAndValidatesLimit() {
        assertTrue(ActivityFeed.merge(null, List.of(Map.of("time_ms", "bad")), 50).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ActivityFeed.merge(null, null, -1));
        assertEquals(0, ActivityFeed.merge(List.of(Map.of("time_ms", 1L)), null, 0).size());
    }
}
