package tracewall.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceProfilesTest {
    @Test void supportsFullLengthUidWithoutUsingItAsAnOverlongDisplayName() {
        String uid = "x".repeat(128);
        DeviceProfiles profiles = new DeviceProfiles(Map.of(uid, "Device"));
        assertEquals("Device", DeviceProfiles.parse(profiles.serialize()).names().get(uid));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("x".repeat(129), "Device")));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse(profiles.serialize() + " trailing"));
    }
    private static final String ONE = "child_one_0123456789abcdef";
    private static final String TWO = "child_two_0123456789abcdef";

    @Test void roundTripsNamesAndEditsWithoutChangingOtherDevices() {
        DeviceProfiles initial = new DeviceProfiles(Map.of(ONE, "First", TWO, "দ্বিতীয়"));
        DeviceProfiles parsed = DeviceProfiles.parse(initial.serialize());
        assertEquals(initial.names(), parsed.names());
        assertEquals("Renamed", parsed.put(ONE, "Renamed").names().get(ONE));
        assertEquals("দ্বিতীয়", parsed.put(ONE, "Renamed").names().get(TWO));
        assertEquals(1, parsed.remove(ONE).names().size());
        assertEquals(2, initial.names().size());
        assertThrows(UnsupportedOperationException.class, () -> parsed.names().clear());
    }
    @Test void validatesIdentifiersNamesCountsAndJsonSchema() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("../child", "Name")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of(ONE, "")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of(ONE, "x\nName")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of(ONE, "x".repeat(81))));
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i <= DeviceProfiles.MAX_DEVICES; i++) tooMany.put("child_" + i + "_0123456789abcdef", "Name");
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(tooMany));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1.5,\"devices\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1,\"devices\":[],\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1,\"devices\":[{\"uid\":\"" + ONE + "\",\"name\":\"A\"},{\"uid\":\"" + ONE + "\",\"name\":\"B\"}]}"));
        assertTrue(DeviceProfiles.parse(null).names().isEmpty());
    }
}
