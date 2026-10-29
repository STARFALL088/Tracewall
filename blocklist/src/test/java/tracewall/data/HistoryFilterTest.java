package tracewall.data;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class HistoryFilterTest {
    private static long at(String time) { return Instant.parse(time).toEpochMilli(); }
    @Test void uidsAreSortedDeduplicatedAndDefensivelyCopied() {
        int[] input = {10002, -1, 10002, 10001};
        HistoryFilter filter = new HistoryFilter("  EXAMPLE.COM ", HistoryFilter.Verdict.ALL,
            new HistoryFilter.Range(0, 100), input);
        input[0] = 999;
        int[] result = filter.uids(); result[0] = 999;
        assertArrayEquals(new int[]{-1, 10001, 10002}, filter.uids());
        assertEquals("example.com", filter.domain);
    }
    @Test void nullAndEmptyUidSelectionsRemainDifferent() {
        HistoryFilter.Range range = new HistoryFilter.Range(0, 10);
        assertNull(new HistoryFilter("", HistoryFilter.Verdict.ALL, range, null).uids());
        assertEquals(0, new HistoryFilter("", HistoryFilter.Verdict.ALL, range, new int[0]).uids().length);
        assertThrows(IllegalArgumentException.class, () -> new HistoryFilter("", HistoryFilter.Verdict.ALL, range, new int[]{-2}));
        assertThrows(IllegalArgumentException.class, () -> new HistoryFilter("", HistoryFilter.Verdict.ALL, range,
            java.util.stream.IntStream.range(1000, 1801).toArray()));
    }
    @Test void literalLikeEscapesMetacharactersAndTheEscapeCharacter() {
        assertEquals("%a\\%b\\_c\\\\d%", new HistoryFilter("a%b_c\\d", HistoryFilter.Verdict.ALL,
            new HistoryFilter.Range(0, 1), null).domainLike());
        assertEquals("%o'reilly%", new HistoryFilter("o'reilly", HistoryFilter.Verdict.ALL,
            new HistoryFilter.Range(0, 1), null).domainLike());
    }
    @Test void exactUidRejectsOverflowAndUnsupportedNegativeValues() {
        assertEquals(-1, HistoryFilter.exactUid(" -1 "));
        assertEquals(Integer.MAX_VALUE, HistoryFilter.exactUid("2147483647"));
        assertNull(HistoryFilter.exactUid("com.example.app"));
        assertThrows(IllegalArgumentException.class, () -> HistoryFilter.exactUid("2147483648"));
        assertThrows(IllegalArgumentException.class, () -> HistoryFilter.exactUid("-2"));
        assertThrows(IllegalArgumentException.class, () -> HistoryFilter.exactUid("999999999999999999999999"));
    }
    @Test void calendarDatesIncludeDstDaysWithoutAssumingTwentyFourHours() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        long now = at("2024-03-10T16:00:00Z");
        HistoryFilter.Range today = HistoryFilter.range(HistoryFilter.Preset.TODAY, now, zone, now, now);
        assertEquals(at("2024-03-10T05:00:00Z"), today.startMs);
        assertEquals(at("2024-03-11T04:00:00Z"), today.endMs);
        HistoryFilter.Range rolling = HistoryFilter.range(HistoryFilter.Preset.LAST_24_HOURS, now, zone, now, now);
        assertEquals(now - 86_400_000L, rolling.startMs);
        assertEquals(now + 1, rolling.endMs);
    }
    @Test void customDatesAreInclusiveAndRejectReversedDays() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Dhaka");
        long first = at("2026-10-27T12:30:00Z"), last = at("2026-10-29T12:30:00Z");
        HistoryFilter.Range range = HistoryFilter.range(HistoryFilter.Preset.CUSTOM, last, zone, first, last);
        assertEquals(at("2026-10-26T18:00:00Z"), range.startMs);
        assertEquals(at("2026-10-29T18:00:00Z"), range.endMs);
        assertThrows(IllegalArgumentException.class, () -> HistoryFilter.range(HistoryFilter.Preset.CUSTOM, last, zone, last, first));
        assertThrows(IllegalArgumentException.class, () -> new HistoryFilter.Range(1, 1));
    }
}
