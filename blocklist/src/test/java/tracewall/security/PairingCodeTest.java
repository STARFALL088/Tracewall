package tracewall.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PairingCodeTest {
    @Test void generatedCodesRoundTripAndNormalizeManualInput() {
        String first = PairingCode.generate();
        assertNotEquals(first, PairingCode.generate());
        PairingCode parsed = PairingCode.parse(new PairingCode("child_123", first).payload());
        assertEquals(first, parsed.code);
        assertEquals("child_123", parsed.childUid);
        assertEquals("ABCDEFGH", new PairingCode("child", "abcdefgh").code);
    }
    @Test void rejectsLegacyQrAndMalformedPathsOrCodes() {
        for (String value : new String[] {"child", "tracewall:2:child:ABCDEFGH",
                "tracewall:1:../child:ABCDEFGH", "tracewall:1:child:ABCDEF01"}) {
            assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(value));
        }
    }
}
