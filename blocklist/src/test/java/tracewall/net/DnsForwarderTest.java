package tracewall.net;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class DnsForwarderTest {
    @Test
    void healthySocketKeepsItsPeerAndIsProtectedOnlyOnce() throws IOException {
        ArrayList<DatagramSocket> bound = new ArrayList<>();
        DnsForwarder.SocketBinder binder = new DnsForwarder.SocketBinder() {
            @Override public void bind(DatagramSocket socket) { bound.add(socket); }
            @Override public void bind(Socket socket) { fail("No TCP connection expected"); }
        };
        DnsForwarder f = new DnsForwarder(Collections.singletonList("127.0.0.1"), 100, binder);
        DatagramSocket second = null;
        try {
            InetAddress resolver = InetAddress.getByName("127.0.0.1");
            DatagramSocket first = f.acquire(resolver);
            f.release(first);
            assertTrue(first.isConnected());
            second = f.acquire(resolver);
            assertSame(first, second);
            assertEquals(1, bound.size());
            assertEquals(resolver, second.getInetAddress());
            assertEquals(IpPacket.PORT_DNS, second.getPort());
        } finally { if (second != null) second.close(); f.close(); }
    }

    @Test
    void aDifferentResolverClosesTheOldPeerInsteadOfReconnectingIt() throws IOException {
        DnsForwarder f = new DnsForwarder(Collections.singletonList("127.0.0.1"), 100, null);
        DatagramSocket second = null;
        try {
            DatagramSocket first = f.acquire(InetAddress.getByName("127.0.0.1"));
            f.release(first);
            InetAddress other = InetAddress.getByName("127.0.0.2");
            second = f.acquire(other);
            assertTrue(first.isClosed());
            assertNotSame(first, second);
            assertEquals(other, second.getInetAddress());
        } finally { if (second != null) second.close(); f.close(); }
    }

    @Test
    void failedUpstreamSocketIsClosedInsteadOfPooled() {
        ArrayList<DatagramSocket> sockets = new ArrayList<>();
        try (FailingBinder binder = new FailingBinder(sockets)) {
            DnsForwarder f = new DnsForwarder(Collections.singletonList("127.0.0.1"), 100, binder);
            try {
                assertThrows(DnsForwarder.ForwardException.class, () ->
                    f.forward(DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A), 2));
                assertEquals(1, sockets.size());
                assertTrue(sockets.get(0).isClosed(), "A failed socket must not poison later DNS requests");
            } finally { f.close(); }
        }
    }

    @Test
    void anotherResolverDoesNotReuseTheFailedSocket() {
        ArrayList<DatagramSocket> sockets = new ArrayList<>();
        try (FailingBinder binder = new FailingBinder(sockets)) {
            DnsForwarder f = new DnsForwarder(Arrays.asList("127.0.0.1", "127.0.0.2"), 100, binder);
            try {
                assertThrows(DnsForwarder.ForwardException.class, () ->
                    f.forward(DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A), 2));
                assertEquals(2, sockets.size());
                assertNotSame(sockets.get(0), sockets.get(1), "A fallback resolver needs a clean socket");
            } finally { f.close(); }
        }
    }

    private static final class FailingBinder implements DnsForwarder.SocketBinder, AutoCloseable {
        private final ArrayList<DatagramSocket> sockets;
        FailingBinder(ArrayList<DatagramSocket> sockets) { this.sockets = sockets; }
        @Override public void bind(DatagramSocket socket) throws IOException {
            sockets.add(socket);
            throw new IOException("Injected upstream socket failure");
        }
        @Override public void bind(Socket socket) throws IOException { throw new IOException("No TCP connection expected"); }
        @Override public void close() { for (DatagramSocket socket : sockets) socket.close(); }
    }

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
