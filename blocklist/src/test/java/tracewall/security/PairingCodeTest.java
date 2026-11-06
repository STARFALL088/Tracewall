package tracewall.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PairingCodeTest {
    // Shape of a real Firebase Auth UID; pairing validation requires 20+ chars.
    private static final String CHILD = "JxhzNfFSrISNvAJLe7lq98FdOZC2";

    @Test void generatedCodesRoundTripAndNormalizeManualInput() {
        String first = PairingCode.generate();
        assertNotEquals(first, PairingCode.generate());
        PairingCode parsed = PairingCode.parse(new PairingCode(CHILD, first).payload());
        assertEquals(first, parsed.code);
        assertEquals(CHILD, parsed.childUid);
        assertEquals("ABCDEFGH", new PairingCode(CHILD, "abcdefgh").code);
    }
    @Test void rejectsLegacyQrAndMalformedPathsOrCodes() {
        for (String value : new String[] {"tracewall:1:child:ABCDEFGH",
                "tracewall:2:" + CHILD + ":ABCDEFGH", "tracewall:1:../child:ABCDEFGH",
                "tracewall:1:" + CHILD + ":ABCDEF01"}) {
            assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(value));
        }
    }
    @Test void rejectsShortOrPathLikeChildUids() {
        assertFalse(PairingCode.validChildUid(null));
        assertFalse(PairingCode.validChildUid(""));
        assertFalse(PairingCode.validChildUid("abc"));
        assertFalse(PairingCode.validChildUid("../devices/x"));
        assertFalse(PairingCode.validChildUid("JxhzNfFSrISNvAJLe7lq98FdOZ.C"));
        assertTrue(PairingCode.validChildUid(CHILD));
        assertTrue(PairingCode.validChildUid("123456789012345678901"));
    }
}
