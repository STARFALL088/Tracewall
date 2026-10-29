package tracewall.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppInventoryTest {
    @Test void rejectsExtraFieldsAndNonIntegralOrNegativeTimestamps() {
        Map<String, Object> row = new AppInventory.Entry("example.app", "App", 10000, 0).record();
        row.put("icon", "not allowed");
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
        row.remove("icon"); row.put("updated_ms", -1L);
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
        row.put("updated_ms", 1.5);
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
        row.put("updated_ms", 0L); row.put("uid", (long) Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, AppInventory.Entry.parse(row).uid);
    }
    @Test void roundTripsAndSortsWithoutLosingSharedUidPackages() {
        AppInventory.Entry a = new AppInventory.Entry("example.one", "Zulu", 12345, 10);
        AppInventory.Entry b = new AppInventory.Entry("example.two", "Alpha", 12345, 20);
        var rows = AppInventory.parse(Arrays.asList(a.record(), b.record()));
        assertEquals("example.two", rows.get(0).packageName);
        assertEquals(12345, rows.get(1).uid);
        assertEquals(10, rows.get(1).updatedMs);
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
    }
    @Test void rejectsInvalidMetadataBeforeItReachesThePicker() {
        assertThrows(IllegalArgumentException.class, () -> new AppInventory.Entry("../other", "App", 12345, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppInventory.Entry("example.app", "App", 9999, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppInventory.Entry("example.app", "x".repeat(201), 12345, 0));
        Map<String, Object> row = new HashMap<>(new AppInventory.Entry("example.app", "App", 12345, 0).record());
        row.put("uid", (long) Integer.MAX_VALUE + 1);
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
        row.put("uid", -(1L << 32) + 12345);
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
        row.put("uid", 12345.5);
        assertThrows(IllegalArgumentException.class, () -> AppInventory.Entry.parse(row));
    }
    @Test void boundsAndDeduplicatesPackagesWithoutMutatingSource() {
        Map<String, Object> row = new AppInventory.Entry("example.app", "App", 12345, 0).record();
        assertThrows(IllegalArgumentException.class, () -> AppInventory.parse(Arrays.asList(row, row)));
        assertThrows(IllegalArgumentException.class, () -> AppInventory.parse(Collections.nCopies(AppInventory.MAX_ENTRIES + 1, row)));
        var parsed = AppInventory.parse(Collections.singletonList(row));
        row.clear();
        assertEquals("example.app", parsed.get(0).packageName);
    }
}
