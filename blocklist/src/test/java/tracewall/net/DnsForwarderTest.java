package tracewall.net;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class DnsForwarderTest {
    @Test
    void closedForwarderRejectsNewQueriesWithoutOpeningSockets() {
        DnsForwarder f = new DnsForwarder(Collections.singletonList("127.0.0.1"), 100, null);
        assertFalse(f.isClosed());
        f.close();
        f.close();
        assertTrue(f.isClosed());
        assertThrows(DnsForwarder.ForwardException.class, () ->
            f.forward(DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A), 2));
    }
}
