package tracewall.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PinStoreTest {
    private final Map<String, String> values = new HashMap<>();
    private PinStore store() {
        return new PinStore(new PinStore.Storage() {
            public String get(String key) { return values.get(key); }
            public void put(String key, String value) { values.put(key, value); }
        });
    }

    @Test void validatesPinAndNeverStoresPlaintext() {
        PinStore p = store();
        for (String bad : new String[] {"123", "1234567", "abcd", "１２３４", "12 34"}) {
            assertThrows(IllegalArgumentException.class, () -> p.set(bad));
        }
        p.set("012345");
        assertFalse(values.containsValue("012345"));
        assertTrue(store().verify("012345", 0));
        assertFalse(p.verify("12345", 0));
        assertThrows(IllegalStateException.class, () -> p.set("5678"));
    }

    @Test void saltChangesForTheSamePin() {
        store().set("1234");
        String first = values.get("verifier");
        values.clear();
        store().set("1234");
        assertNotEquals(first, values.get("verifier"));
    }

    @Test void fiveFailuresLockOutAcrossInstancesThenExpire() {
        PinStore p = store();
        p.set("1234");
        for (int i = 0; i < 5; i++) assertFalse(p.verify("0000", 1000));
        assertEquals(30_000, store().lockedFor(1000));
        assertFalse(store().verify("1234", 30_999));
        assertTrue(store().verify("1234", 31_000));
        assertEquals(0, p.lockedFor(31_000));
    }

    @Test void successResetsFailedAttemptCount() {
        PinStore p = store();
        p.set("1234");
        for (int i = 0; i < 4; i++) p.verify("9999", 0);
        assertTrue(p.verify("1234", 0));
        assertFalse(p.verify("9999", 0));
        assertEquals(0, p.lockedFor(0));
    }
}
