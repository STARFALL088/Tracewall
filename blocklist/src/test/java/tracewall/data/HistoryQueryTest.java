package tracewall.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryQueryTest {
    private static HistoryFilter filter(String text, int[] uids) {
        return new HistoryFilter(text, HistoryFilter.Verdict.BLOCKED, new HistoryFilter.Range(10, 100), uids);
    }
    @Test void userTextNeverAppearsInSqlAndArgumentsAreCopied() {
        HistoryQuery.Sql query = HistoryQuery.page(filter("x%' OR 1=1 --", new int[]{10001}),
            42, null, HistoryQuery.Direction.OLDER, 20);
        assertFalse(query.text.contains("OR 1=1"));
        Object[] args = query.arguments();
        assertArrayEquals(new Object[]{42L, 10L, 100L, "%x\\%' or 1=1 --%", 1, 10001, 20}, args);
        args[0] = -1;
        assertEquals(42L, query.arguments()[0]);
    }
    @Test void keysetUsesTimestampAndIdInBothDirections() {
        HistoryQuery.Cursor anchor = new HistoryQuery.Cursor(50, 17);
        HistoryQuery.Sql older = HistoryQuery.page(filter("", null), 42, anchor, HistoryQuery.Direction.OLDER, 20);
        assertTrue(older.text.contains("timeMs < ? OR (timeMs = ? AND id < ?)"));
        assertTrue(older.text.endsWith("ORDER BY timeMs DESC, id DESC LIMIT ?"));
        HistoryQuery.Sql newer = HistoryQuery.page(filter("", null), 42, anchor, HistoryQuery.Direction.NEWER, 20);
        assertTrue(newer.text.contains("timeMs > ? OR (timeMs = ? AND id > ?)"));
        assertTrue(newer.text.endsWith("ORDER BY timeMs ASC, id ASC LIMIT ?"));
    }
    @Test void emptyAppSelectionCannotAccidentallySelectEveryApp() {
        assertTrue(HistoryQuery.page(filter("", new int[0]), 42, null, HistoryQuery.Direction.OLDER, 1).text.contains("AND 0"));
        assertFalse(HistoryQuery.page(filter("", null), 42, null, HistoryQuery.Direction.OLDER, 1).text.contains("uid IN"));
    }
    @Test void statsApplyAllFiltersWithoutThePageLimit() {
        HistoryQuery.Sql stats = HistoryQuery.statistics(filter("site", new int[]{10001}), 42);
        assertTrue(stats.text.contains("COUNT(*) AS total"));
        assertTrue(stats.text.contains("AND blocked = ?"));
        assertTrue(stats.text.contains("id <= ?"));
        assertFalse(stats.text.contains("LIMIT"));
        assertFalse(stats.text.contains("ORDER BY"));
    }
    @Test void invalidBoundsFailBeforeExecutingSql() {
        assertThrows(IllegalArgumentException.class, () -> HistoryQuery.page(filter("", null), -1, null, HistoryQuery.Direction.OLDER, 1));
        assertThrows(IllegalArgumentException.class, () -> HistoryQuery.page(filter("", null), 0, null, HistoryQuery.Direction.OLDER, 0));
        assertThrows(IllegalArgumentException.class, () -> HistoryQuery.page(filter("", null), 0, null, HistoryQuery.Direction.OLDER, 502));
        assertThrows(IllegalArgumentException.class, () -> new HistoryQuery.Cursor(0, 0));
    }
}
