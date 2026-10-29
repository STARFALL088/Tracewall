package tracewall.blocklist;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class RuleListsTest {
    @Test void rejectsEditsAndPushesBeforeSnapshot() {
        RuleLists r = new RuleLists();
        assertThrows(IllegalStateException.class, () -> r.addAllow("a.test"));
        assertThrows(IllegalStateException.class, r::allows);
        r.load(Collections.emptyList(), Collections.emptyList());
        r.pause();
        assertThrows(IllegalStateException.class, () -> r.block(12345));
    }
    @Test void editsPreserveOtherEntriesAndOtherList() {
        RuleLists r = new RuleLists();
        r.load(Arrays.asList("a.test"), Arrays.asList(12345));
        r.addAllow("b.test");
        r.addAllow("b.test");
        r.block(23456);
        assertEquals(Arrays.asList("a.test", "b.test"), r.allows());
        assertEquals(Arrays.asList(12345, 23456), r.blockedUids());
        r.removeAllow("a.test");
        r.unblock(12345);
        assertEquals(Arrays.asList("b.test"), r.allows());
        assertEquals(Arrays.asList(23456), r.blockedUids());
        r.allows().clear();
        assertEquals(1, r.allows().size());
    }
}
