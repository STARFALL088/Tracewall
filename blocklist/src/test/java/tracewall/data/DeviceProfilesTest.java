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
    @Test void roundTripsNamesAndEditsWithoutChangingOtherDevices() {
        DeviceProfiles initial = new DeviceProfiles(Map.of("child_one", "First", "child_two", "দ্বিতীয়"));
        DeviceProfiles parsed = DeviceProfiles.parse(initial.serialize());
        assertEquals(initial.names(), parsed.names());
        assertEquals("Renamed", parsed.put("child_one", "Renamed").names().get("child_one"));
        assertEquals("দ্বিতীয়", parsed.put("child_one", "Renamed").names().get("child_two"));
        assertEquals(1, parsed.remove("child_one").names().size());
        assertEquals(2, initial.names().size());
        assertThrows(UnsupportedOperationException.class, () -> parsed.names().clear());
    }
    @Test void validatesIdentifiersNamesCountsAndJsonSchema() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("../child", "Name")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("child", "")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("child", "x\nName")));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(Map.of("child", "x".repeat(81))));
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i <= DeviceProfiles.MAX_DEVICES; i++) tooMany.put("child_" + i, "Name");
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfiles(tooMany));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1.5,\"devices\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1,\"devices\":[],\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> DeviceProfiles.parse("{\"version\":1,\"devices\":[{\"uid\":\"same\",\"name\":\"A\"},{\"uid\":\"same\",\"name\":\"B\"}]}"));
        assertTrue(DeviceProfiles.parse(null).names().isEmpty());
    }
}
