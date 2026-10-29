package tracewall.net;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodecBoundsTest {
    @Test
    void invalidSlicesIncludingIntegerOverflowAreRejected() {
        byte[] buf = new byte[64];
        int[] values = {Integer.MIN_VALUE, -1, 0, 1, 12, 63, 64, 65,
                Integer.MAX_VALUE - 8, Integer.MAX_VALUE};
        for (int off : values) {
            for (int len : values) {
                if (off >= 0 && len >= 0 && (long) off + len <= buf.length) continue;
                assertNull(DnsMessage.parse(buf, off, len), "DNS " + off + ":" + len);
                assertNull(IpPacket.parse(buf, off, len), "IP " + off + ":" + len);
            }
        }
        assertNull(DnsMessage.parse(null, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertNull(IpPacket.parse(null, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void everyTruncatedQuestionIsRejectedWithAndWithoutBackingPadding() {
        byte[] query = DnsMessageTest.query(7, "logical.slice.test", DnsMessage.TYPE_A);
        for (int off : new int[] {0, 1, 8, 31}) {
            byte[] padded = new byte[off + query.length + 32];
            System.arraycopy(query, 0, padded, off, query.length);
            for (int len = 0; len < query.length; len++) {
                assertNull(DnsMessage.parse(padded, off, len), "padded " + off + ":" + len);
                assertNull(DnsMessage.parse(Arrays.copyOf(padded, off + len), off, len),
                        "exact backing " + off + ":" + len);
            }
            DnsMessage parsed = DnsMessage.parse(padded, off, query.length);
            assertNotNull(parsed);
            assertEquals("logical.slice.test", parsed.qName());
            assertArrayEquals(query, parsed.raw());
        }
    }

    @Test
    void pointersAreMessageRelativeAndNeverReadOutsideTheLogicalSlice() {
        byte[] name = {1, 'a', 0, (byte) 0xC0, 0, (byte) 0xC0, 3};
        for (int base : new int[] {0, 1, 16, 64}) {
            byte[] padded = new byte[base + name.length + 32];
            System.arraycopy(name, 0, padded, base, name.length);
            int[] cursor = {base + 5};
            assertEquals("a", DnsMessage.readName(padded, base, base + name.length, cursor));
            assertEquals(base + name.length, cursor[0], "cursor resumes after the first pointer");

            cursor[0] = base + 5;
            assertNull(DnsMessage.readName(padded, base, base + 6, cursor), "split pointer");

            // A nonzero base must not make an out-of-message forward target look backward.
            padded[base + 3] = (byte) 0xC0;
            padded[base + 4] = 20;
            cursor[0] = base + 3;
            assertNull(DnsMessage.readName(padded, base, base + name.length, cursor));
            padded[base + 4] = 3;
            assertNull(DnsMessage.readName(padded, base, base + name.length, cursor), "self pointer");
        }
    }

    @Test
    void pointerChainsEnforceEveryBackwardHopAndTheHopLimit() {
        byte[] chain = new byte[1 + 65 * 2];
        for (int i = 1; i < chain.length; i += 2) {
            chain[i] = (byte) 0xC0;
            chain[i + 1] = (byte) (i == 1 ? 0 : i - 2);
        }
        assertEquals(".", DnsMessage.readName(chain, 0, chain.length, new int[] {127}));
        assertNull(DnsMessage.readName(chain, 0, chain.length, new int[] {129}));

        // The first hop is backward, but its target points forward again.
        byte[] forwardHop = {0, (byte) 0xC0, 3, 0, (byte) 0xC0, 1};
        assertNull(DnsMessage.readName(forwardHop, 0, forwardHop.length, new int[] {4}));
    }

    @Test
    void expandedNameLimitCountsWireBytesRatherThanEscapedText() {
        byte[] name = new byte[255];
        int p = 0;
        for (int length : new int[] {63, 63, 63, 61}) {
            name[p++] = (byte) length;
            Arrays.fill(name, p, p + length, (byte) 0xFF);
            p += length;
        }
        int[] cursor = {0};
        String decoded = DnsMessage.readName(name, 0, name.length, cursor);
        assertNotNull(decoded, "255 wire bytes is a valid name even when escapes expand its text");
        assertEquals(name.length, cursor[0]);
        assertTrue(decoded.startsWith("\\255"));
        assertArrayEquals(name, DnsMessage.encodeName(decoded));
        byte[] overlong = Arrays.copyOf(name, name.length + 1);
        overlong[192] = 62;
        overlong[254] = (byte) 'a';
        assertNull(DnsMessage.readName(overlong, 0, overlong.length, new int[] {0}));
    }

    @Test
    void escapedWireNamesRoundTripWithoutChangingSyntheticQuestions() {
        Random random = new Random(0xE5CA9EL);
        for (int fixture = 0; fixture < 200; fixture++) {
            java.io.ByteArrayOutputStream wire = new java.io.ByteArrayOutputStream();
            int labels = 1 + random.nextInt(5);
            for (int label = 0; label < labels; label++) {
                byte[] bytes = new byte[1 + random.nextInt(16)];
                random.nextBytes(bytes);
                wire.write(bytes.length);
                wire.write(bytes, 0, bytes.length);
            }
            wire.write(0);
            byte[] name = wire.toByteArray();
            String text = DnsMessage.readName(name, 0, name.length, new int[] {0});
            assertNotNull(text, "fixture " + fixture);
            assertArrayEquals(name, DnsMessage.encodeName(text));
            assertArrayEquals(name, DnsMessage.encodeName(text + "."));

            byte[] query = new byte[DnsMessage.HEADER_LEN + name.length + 4];
            DnsMessage.putU16(query, 4, 1);
            System.arraycopy(name, 0, query, DnsMessage.HEADER_LEN, name.length);
            DnsMessage.putU16(query, query.length - 4, DnsMessage.TYPE_A);
            DnsMessage.putU16(query, query.length - 2, DnsMessage.CLASS_IN);
            DnsMessage parsed = DnsMessage.parse(query);
            assertNotNull(parsed);
            for (byte[] response : new byte[][] {DnsMessage.buildEmptyResponse(parsed),
                    DnsMessage.buildAResponse(parsed, new byte[4])}) {
                assertNotNull(response);
                assertArrayEquals(Arrays.copyOfRange(query, DnsMessage.HEADER_LEN, query.length),
                        Arrays.copyOfRange(response, DnsMessage.HEADER_LEN, query.length));
                assertEquals(text, DnsMessage.parse(response).qName());
            }
        }
    }

    @Test
    void ipLengthsCannotBorrowBytesFromBackingPaddingOrOutsideTheIpPayload() {
        byte[] dns = DnsMessageTest.query(1, "slice.test", DnsMessage.TYPE_A);
        for (byte[] packet : new byte[][] {IpPacketTest.v4Query(40000, 53, dns), v6Query(dns)}) {
            int header = (packet[0] & 0xF0) == 0x40 ? IpPacket.IPV4_HEADER_LEN : IpPacket.IPV6_HEADER_LEN;
            for (int off : new int[] {0, 1, 23}) {
                byte[] padded = new byte[off + packet.length + 16];
                System.arraycopy(packet, 0, padded, off, packet.length);
                for (int len = 0; len < packet.length; len++) {
                    assertNull(IpPacket.parse(padded, off, len), "truncated " + header + ":" + off + ":" + len);
                }
                assertArrayEquals(dns, IpPacket.parse(padded, off, packet.length).payload());
            }
            byte[] shortPayload = packet.clone();
            DnsMessage.putU16(shortPayload, header == 20 ? 2 : 4, header == 20 ? header + 7 : 7);
            assertNull(IpPacket.parse(shortPayload), "IP payload must contain a complete UDP header");
            DnsMessage.putU16(shortPayload, header == 20 ? 2 : 4, header == 20 ? header + 8 : 8);
            assertNull(IpPacket.parse(shortPayload), "UDP length cannot exceed the declared IP payload");
            byte[] shortUdp = packet.clone();
            for (int udpLen : new int[] {0, 1, 7, packet.length - header + 1, 65535}) {
                DnsMessage.putU16(shortUdp, header + 4, udpLen);
                assertNull(IpPacket.parse(shortUdp), "invalid UDP length " + udpLen);
            }
        }
    }

    @Test
    void zeroIpLengthsStillUseOnlyTheSuppliedSliceAndDeclaredLengthsIgnorePadding() {
        byte[] dns = DnsMessageTest.query(1, "slice.test", DnsMessage.TYPE_A);
        for (byte[] packet : new byte[][] {IpPacketTest.v4Query(40000, 53, dns), v6Query(dns)}) {
            byte[] padded = Arrays.copyOf(packet, packet.length + 32);
            assertArrayEquals(dns, IpPacket.parse(padded).payload());
            DnsMessage.putU16(padded, (packet[0] & 0xF0) == 0x40 ? 2 : 4, 0);
            assertArrayEquals(dns, IpPacket.parse(padded, 0, packet.length).payload());
            assertNull(IpPacket.parse(padded, 0, packet.length - 1));
        }
    }

    @Test
    void deterministicMalformedSlicesBehaveExactlyLikeIsolatedBuffers() {
        Random random = new Random(0x51CEB00CL);
        byte[][] fixtures = {
            DnsMessageTest.query(1, "property.test", DnsMessage.TYPE_A),
            IpPacketTest.v4Query(40000, 53, new byte[16]),
            v6Query(new byte[16]),
            new byte[96]
        };
        for (int i = 0; i < 2000; i++) {
            byte[] bytes = fixtures[i % fixtures.length].clone();
            if (i % fixtures.length == 3) random.nextBytes(bytes);
            else bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
            int off = random.nextInt(33);
            int len = random.nextInt(bytes.length + 1);
            byte[] padded = new byte[off + bytes.length + 32];
            random.nextBytes(padded);
            System.arraycopy(bytes, 0, padded, off, bytes.length);
            byte[] isolated = Arrays.copyOf(bytes, len);

            DnsMessage expectedDns = DnsMessage.parse(isolated);
            DnsMessage actualDns = DnsMessage.parse(padded, off, len);
            if (expectedDns == null) assertNull(actualDns, "DNS fixture " + i);
            else {
                assertNotNull(actualDns, "DNS fixture " + i);
                assertArrayEquals(expectedDns.raw(), actualDns.raw());
                assertEquals(expectedDns.qName(), actualDns.qName());
                assertEquals(expectedDns.qType(), actualDns.qType());
                assertEquals(expectedDns.qClass(), actualDns.qClass());
            }
            IpPacket expectedIp = IpPacket.parse(isolated);
            IpPacket actualIp = IpPacket.parse(padded, off, len);
            if (expectedIp == null) assertNull(actualIp, "IP fixture " + i);
            else {
                assertNotNull(actualIp, "IP fixture " + i);
                assertEquals(expectedIp.version(), actualIp.version());
                assertEquals(expectedIp.srcPort(), actualIp.srcPort());
                assertEquals(expectedIp.dstPort(), actualIp.dstPort());
                assertArrayEquals(expectedIp.srcIpBytes(), actualIp.srcIpBytes());
                assertArrayEquals(expectedIp.dstIpBytes(), actualIp.dstIpBytes());
                assertArrayEquals(expectedIp.payload(), actualIp.payload());
            }
        }
    }

    static byte[] v6Query(byte[] payload) {
        int udpLen = IpPacket.UDP_HEADER_LEN + payload.length;
        byte[] packet = new byte[IpPacket.IPV6_HEADER_LEN + udpLen];
        packet[0] = 0x60;
        DnsMessage.putU16(packet, 4, udpLen);
        packet[6] = (byte) IpPacket.PROTO_UDP;
        packet[7] = 64;
        packet[23] = 1;
        packet[39] = 2;
        DnsMessage.putU16(packet, 40, 40000);
        DnsMessage.putU16(packet, 42, 53);
        DnsMessage.putU16(packet, 44, udpLen);
        System.arraycopy(payload, 0, packet, 48, payload.length);
        return packet;
    }
}
