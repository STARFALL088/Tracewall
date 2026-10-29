package tracewall.blocklist;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyScheduleTest {
    private static long instant(String iso) { return Instant.parse(iso).toEpochMilli(); }

    @Test void daytimeStartsInclusivelyAndEndsExclusivelyOnSelectedDaysOnly() {
        WeeklySchedule schedule = new WeeklySchedule(true, 1, 9 * 60, 17 * 60);
        assertFalse(schedule.isActive(1, 539));
        assertTrue(schedule.isActive(1, 540));
        assertTrue(schedule.isActive(1, 1019));
        assertFalse(schedule.isActive(1, 1020));
        assertFalse(schedule.isActive(2, 540));
    }

    @Test void overnightUsesTheStartDayAndWrapsSundayIntoMonday() {
        WeeklySchedule schedule = new WeeklySchedule(true, 1 << 6, 22 * 60, 6 * 60);
        assertFalse(schedule.isActive(7, 1319));
        assertTrue(schedule.isActive(7, 1320));
        assertTrue(schedule.isActive(7, 1439));
        assertTrue(schedule.isActive(1, 0));
        assertTrue(schedule.isActive(1, 359));
        assertFalse(schedule.isActive(1, 360));
        assertFalse(schedule.isActive(1, 1320));
        assertFalse(schedule.isActive(7, 0));
    }

    @Test void everyDayBitRefersToItsOwnIsoWeekday() {
        for (int selected = 1; selected <= 7; selected++) {
            WeeklySchedule schedule = new WeeklySchedule(true, 1 << (selected - 1), 0, 1);
            for (int actual = 1; actual <= 7; actual++) {
                assertEquals(selected == actual, schedule.isActive(actual, 0));
                assertFalse(schedule.isActive(actual, 1));
            }
        }
    }

    @Test void disabledAndInvalidSchedulesCannotSilentlyMeanFullDay() {
        assertFalse(new WeeklySchedule(false, WeeklySchedule.ALL_DAYS, 0, 1).isActive(1, 0));
        for (int[] args : new int[][] {{0, 1, 2}, {128, 1, 2}, {-1, 1, 2}, {1, -1, 2},
                {1, 1, 1440}, {1, 1440, 1}, {1, 10, 10}})
            assertThrows(IllegalArgumentException.class, () -> new WeeklySchedule(true, args[0], args[1], args[2]));
        WeeklySchedule valid = new WeeklySchedule(true, 1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> valid.isActive(0, 0));
        assertThrows(IllegalArgumentException.class, () -> valid.isActive(8, 0));
        assertThrows(IllegalArgumentException.class, () -> valid.isActive(1, 1440));
        assertThrows(IllegalArgumentException.class, () -> valid.isActive(0L, null));
    }

    @Test void deviceZoneIsEvaluatedForEachInstantNotCapturedAtConstruction() {
        WeeklySchedule monday = new WeeklySchedule(true, 1, 9 * 60, 10 * 60);
        long now = instant("2026-10-26T09:00:00Z");
        assertTrue(monday.isActive(now, TimeZone.getTimeZone("UTC")));
        assertFalse(monday.isActive(now, TimeZone.getTimeZone("Asia/Dhaka")));
        assertTrue(monday.isActive(now, TimeZone.getTimeZone("UTC")));
    }

    @Test void springForwardSkippedMinutesNeverActivateAndFallBackMinutesRepeat() {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        WeeklySchedule skipped = new WeeklySchedule(true, 1 << 6, 2 * 60, 3 * 60);
        assertFalse(skipped.isActive(instant("2026-03-08T06:59:59Z"), ny));
        assertFalse(skipped.isActive(instant("2026-03-08T07:00:00Z"), ny));
        WeeklySchedule repeated = new WeeklySchedule(true, 1 << 6, 90, 105);
        assertTrue(repeated.isActive(instant("2026-11-01T05:30:00Z"), ny));
        assertFalse(repeated.isActive(instant("2026-11-01T05:45:00Z"), ny));
        assertTrue(repeated.isActive(instant("2026-11-01T06:30:00Z"), ny));
        assertFalse(repeated.isActive(instant("2026-11-01T06:45:00Z"), ny));
    }
}
