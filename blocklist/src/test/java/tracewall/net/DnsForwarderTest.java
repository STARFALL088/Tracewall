package tracewall.net;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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

    @Test
    void tcpReplyIsReadAcrossShortReadsWithoutConsumingAnotherFrame() throws IOException {
        byte[] response = response(0x4242);
        byte[] framed = frame(response);
        byte[] twoFrames = new byte[framed.length * 2];
        System.arraycopy(framed, 0, twoFrames, 0, framed.length);
        System.arraycopy(framed, 0, twoFrames, framed.length, framed.length);
        ByteArrayInputStream source = new ByteArrayInputStream(twoFrames);
        InputStream shortReads = new FilterInputStream(source) {
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                return super.read(bytes, off, Math.min(3, len));
            }
        };
        byte[] parsed = DnsForwarder.readTcpReply(shortReads, 0x4242);
        assertNotNull(parsed);
        assertArrayEquals(DnsMessage.normalizeResponse(response, 0x4242, true), parsed);
        assertEquals(framed.length, source.available());
    }

    @Test
    void tcpReplyRejectsWrongIdQueryFlagsTruncationAndMalformedQuestions() throws IOException {
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(frame(response(1))), 2));
        byte[] wrongFlags = response(1);
        DnsMessage.putU16(wrongFlags, 2, DnsMessage.FLAGS_RD);
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(frame(wrongFlags)), 1));
        DnsMessage.putU16(wrongFlags, 2, DnsMessage.FLAGS_QR | DnsMessage.FLAGS_TC);
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(frame(wrongFlags)), 1));
        byte[] malformed = Arrays.copyOf(response(1), DnsMessage.HEADER_LEN);
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(frame(malformed)), 1));
    }

    @Test
    void tcpReplyRejectsMissingLengthInvalidLengthAndPrematureEof() throws IOException {
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(new byte[0]), 1));
        assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(new byte[] {0}), 1));
        for (int len : new int[] {0, 11, IpPacket.MAX_DNS_BYTES + 1, 65535}) {
            byte[] prefix = {(byte) (len >>> 8), (byte) len};
            assertNull(DnsForwarder.readTcpReply(new ByteArrayInputStream(prefix), 1));
        }
        byte[] complete = frame(response(1));
        for (int len = 2; len < complete.length; len++) {
            byte[] incomplete = Arrays.copyOf(complete, len);
            assertThrows(IOException.class, () ->
                    DnsForwarder.readTcpReply(new ByteArrayInputStream(incomplete), 1));
        }
    }

    @Test
    void tcpReplyMakesProgressWhenABulkReadReturnsZero() throws IOException {
        byte[] response = response(1);
        InputStream zeroReads = new FilterInputStream(new ByteArrayInputStream(frame(response))) {
            @Override public int read(byte[] bytes, int off, int len) { return 0; }
        };
        assertArrayEquals(DnsMessage.normalizeResponse(response, 1, true),
                DnsForwarder.readTcpReply(zeroReads, 1));
    }

    private static byte[] response(int id) {
        return DnsMessage.buildAResponse(DnsMessage.parse(
                DnsMessageTest.query(id, "tcp.test", DnsMessage.TYPE_A)), new byte[4]);
    }

    private static byte[] frame(byte[] body) {
        byte[] frame = new byte[body.length + 2];
        DnsMessage.putU16(frame, 0, body.length);
        System.arraycopy(body, 0, frame, 2, body.length);
        return frame;
    }
}
