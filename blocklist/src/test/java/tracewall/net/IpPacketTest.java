package tracewall.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpPacketTest {

    /** Build a real IPv4+UDP+DNS query packet, checksummed properly. */
    static byte[] v4Query(int srcPort, int dstPort, byte[] dnsPayload) {
        int udpLen = IpPacket.UDP_HEADER_LEN + dnsPayload.length;
        int total = IpPacket.IPV4_HEADER_LEN + udpLen;
        byte[] out = new byte[total];
        out[0] = 0x45;
        DnsMessage.putU16(out, 2, total);
        DnsMessage.putU16(out, 4, 0x1234);
        DnsMessage.putU16(out, 6, 0x4000);
        out[8] = 64;
        out[9] = (byte) IpPacket.PROTO_UDP;
        out[12] = 10; out[13] = 0; out[14] = 0; out[15] = 2;      // 10.0.0.2
        out[16] = (byte) 192; out[17] = (byte) 168; out[18] = 1; out[19] = 1;
        int c = IpPacket.checksum(out, 0, IpPacket.IPV4_HEADER_LEN, 0);
        DnsMessage.putU16(out, 10, c);

        int u = IpPacket.IPV4_HEADER_LEN;
        DnsMessage.putU16(out, u, srcPort);
        DnsMessage.putU16(out, u + 2, dstPort);
        DnsMessage.putU16(out, u + 4, udpLen);
        DnsMessage.putU16(out, u + 6, 0);
        System.arraycopy(dnsPayload, 0, out, u + 8, dnsPayload.length);
        int us = IpPacket.checksum(out, u, udpLen, pseudoV4(udpLen));
        DnsMessage.putU16(out, u + 6, us == 0 ? 0xFFFF : us);
        return out;
    }

    /** 32-bit pseudo-header sum: src(4) + dst(4) + proto + len. */
    private static int pseudoV4(int udpLen) {
        int sum = 0;
        byte[] src = {10, 0, 0, 2};
        byte[] dst = {(byte) 192, (byte) 168, 1, 1};
        for (int i = 0; i < 4; i += 2) {
            sum += ((src[i] & 0xFF) << 8) | (src[i + 1] & 0xFF);
            sum += ((dst[i] & 0xFF) << 8) | (dst[i + 1] & 0xFF);
        }
        sum += IpPacket.PROTO_UDP;
        sum += udpLen;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return sum;
    }

    @Test
    void parsesV4UdpPacket() {
        byte[] dns = DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A);
        byte[] pkt = v4Query(40000, 53, dns);
        IpPacket p = IpPacket.parse(pkt);
        assertNotNull(p);
        assertTrue(p.isV4());
        assertTrue(p.isUdp());
        assertEquals(40000, p.srcPort());
        assertEquals(53, p.dstPort());
        assertEquals("10.0.0.2", p.srcIpText());
        assertEquals("192.168.1.1", p.dstIpText());
        assertEquals(64, p.ttl());
        assertEquals(0x1234, p.identification());
        assertArrayEquals(dns, p.payload());
        assertTrue(p.isDnsQuery());
    }

    @Test
    void checksumOfAKnownHeaderIsStable() {
        // Verifies the one's-complement fold itself, independent of any packet:
        // summing a region then summing again over a buffer containing the
        // already-folded result must yield 0xFFFF (i.e. checksum == 0).
        byte[] pkt = v4Query(53, 40000, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        int verify = IpPacket.checksum(pkt, 0, IpPacket.IPV4_HEADER_LEN, 0);
        assertEquals(0, verify, "a correct IPv4 header checksum re-checksums to 0");
    }

    @Test
    void udpChecksumReVerifiesToZero() {
        byte[] dns = DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A);
        byte[] pkt = v4Query(40000, 53, dns);
        int u = IpPacket.IPV4_HEADER_LEN;
        int len = pkt.length - u;
        // Re-fold with the stored checksum already in place.
        int sum = 0;
        byte[] src = {10, 0, 0, 2};
        byte[] dst = {(byte) 192, (byte) 168, 1, 1};
        for (int i = 0; i < 4; i += 2) {
            sum += ((src[i] & 0xFF) << 8) | (src[i + 1] & 0xFF);
            sum += ((dst[i] & 0xFF) << 8) | (dst[i + 1] & 0xFF);
        }
        sum += IpPacket.PROTO_UDP;
        sum += len;
        int verify = IpPacket.checksum(pkt, u, len, sum);
        assertEquals(0, verify, "a correct UDP checksum re-checksums to 0");
    }

    @Test
    void rejectsNonIpVersion() {
        byte[] junk = new byte[64];
        junk[0] = 0x25;              // version 2
        assertNull(IpPacket.parse(junk));
        junk[0] = 0x00;
        assertNull(IpPacket.parse(junk));
    }

    @Test
    void rejectsTcpAndOtherProtocols() {
        byte[] pkt = v4Query(40000, 53, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        pkt[9] = (byte) IpPacket.PROTO_TCP;
        assertNull(IpPacket.parse(pkt), "TCP/53 is dropped, not half-handled");
    }

    @Test
    void rejectsFragments() {
        byte[] pkt = v4Query(40000, 53, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        DnsMessage.putU16(pkt, 6, 0x2000);          // MF set
        assertNull(IpPacket.parse(pkt));
        pkt[6] = 0x00; pkt[7] = 0x01;              // offset 1
        assertNull(IpPacket.parse(pkt));
    }

    @Test
    void rejectsTruncatedHeaders() {
        assertNull(IpPacket.parse(new byte[10]));
        assertNull(IpPacket.parse(new byte[1]));
        assertNull(IpPacket.parse(null));
    }

    @Test
    void rejectsIhlThatExceedsTheBuffer() {
        byte[] pkt = v4Query(40000, 53, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        pkt[0] = 0x4F;                 // IHL = 15 -> 60 bytes, packet is smaller
        assertNull(IpPacket.parse(pkt));
    }

    @Test
    void toleratesZeroTotalLength() {
        // Some TUN writers leave total length 0 and let the kernel fill it in.
        byte[] pkt = v4Query(40000, 53, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        DnsMessage.putU16(pkt, 2, 0);
        IpPacket p = IpPacket.parse(pkt);
        assertNotNull(p);
        assertEquals(53, p.dstPort());
    }

    @Test
    void parsesV6UdpPacket() {
        byte[] dns = DnsMessageTest.query(1, "v6.test", DnsMessage.TYPE_AAAA);
        int udpLen = IpPacket.UDP_HEADER_LEN + dns.length;
        byte[] out = new byte[IpPacket.IPV6_HEADER_LEN + udpLen];
        out[0] = 0x60;
        DnsMessage.putU16(out, 4, udpLen);
        out[6] = (byte) IpPacket.PROTO_UDP;
        out[7] = 64;
        for (int i = 0; i < 16; i++) { out[8 + i] = (byte) (i + 1); }
        for (int i = 0; i < 16; i++) { out[24 + i] = (byte) (0xA0 + i); }
        int u = IpPacket.IPV6_HEADER_LEN;
        DnsMessage.putU16(out, u, 5353);
        DnsMessage.putU16(out, u + 2, 53);
        DnsMessage.putU16(out, u + 4, udpLen);
        System.arraycopy(dns, 0, out, u + 8, dns.length);

        IpPacket p = IpPacket.parse(out);
        assertNotNull(p);
        assertFalse(p.isV4());
        assertEquals(6, p.version());
        assertEquals(5353, p.srcPort());
        assertEquals(53, p.dstPort());
        assertArrayEquals(dns, p.payload());
    }

    @Test
    void rejectsV6WithExtensionHeader() {
        byte[] out = new byte[IpPacket.IPV6_HEADER_LEN + 16];
        out[0] = 0x60;
        DnsMessage.putU16(out, 4, 16);
        out[6] = 43;                   // routing header, not UDP
        assertNull(IpPacket.parse(out));
    }

    @Test
    void buildsAReversedV4Response() {
        byte[] dns = DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A);
        byte[] pkt = v4Query(40000, 53, dns);
        IpPacket p = IpPacket.parse(pkt);
        assertNotNull(p);

        byte[] answer = DnsMessage.buildAResponse(
                DnsMessage.parse(dns), new byte[] {0, 0, 0, 0});
        assertNotNull(answer);
        byte[] resp = p.buildResponse(p.dstIpBytes(), p.srcIpBytes(), answer, 0x4321);
        assertNotNull(resp);

        IpPacket r = IpPacket.parse(resp);
        assertNotNull(r);
        assertTrue(r.isV4());
        // Ports swapped, addresses swapped: the client must see a reply.
        assertEquals(53, r.srcPort());
        assertEquals(40000, r.dstPort());
        assertEquals("192.168.1.1", r.srcIpText());
        assertEquals("10.0.0.2", r.dstIpText());
        assertEquals(0x4321, r.identification());
        assertArrayEquals(answer, r.payload());
    }

    @Test
    void builtResponseHeaderChecksumIsCorrect() {
        byte[] dns = DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A);
        IpPacket p = IpPacket.parse(v4Query(40000, 53, dns));
        byte[] resp = p.buildResponse(p.dstIpBytes(), p.srcIpBytes(),
                DnsMessage.buildAResponse(DnsMessage.parse(dns), new byte[4]), 1);
        int verify = IpPacket.checksum(resp, 0, IpPacket.IPV4_HEADER_LEN, 0);
        assertEquals(0, verify, "a bad IPv4 checksum is silently dropped by clients");
    }

    @Test
    void builtResponseUdpChecksumIsCorrect() {
        byte[] dns = DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A);
        IpPacket p = IpPacket.parse(v4Query(40000, 53, dns));
        byte[] resp = p.buildResponse(p.dstIpBytes(), p.srcIpBytes(),
                DnsMessage.buildAResponse(DnsMessage.parse(dns), new byte[4]), 1);
        int u = IpPacket.IPV4_HEADER_LEN;
        int len = resp.length - u;
        int sum = 0;
        byte[] src = p.dstIpBytes();      // response source
        byte[] dst = p.srcIpBytes();
        for (int i = 0; i < 4; i += 2) {
            sum += ((src[i] & 0xFF) << 8) | (src[i + 1] & 0xFF);
            sum += ((dst[i] & 0xFF) << 8) | (dst[i + 1] & 0xFF);
        }
        sum += IpPacket.PROTO_UDP;
        sum += len;
        assertEquals(0, IpPacket.checksum(resp, u, len, sum),
                "a bad UDP checksum means the client discards our answer");
    }

    @Test
    void buildsV6ResponseWithPortsReversed() {
        byte[] dns = DnsMessageTest.query(1, "v6.test", DnsMessage.TYPE_AAAA);
        byte[] out = new byte[IpPacket.IPV6_HEADER_LEN + IpPacket.UDP_HEADER_LEN + dns.length];
        out[0] = 0x60;
        DnsMessage.putU16(out, 4, IpPacket.UDP_HEADER_LEN + dns.length);
        out[6] = (byte) IpPacket.PROTO_UDP;
        out[7] = 64;
        for (int i = 0; i < 16; i++) out[8 + i] = (byte) (i + 1);
        for (int i = 0; i < 16; i++) out[24 + i] = (byte) (0xA0 + i);
        int u = IpPacket.IPV6_HEADER_LEN;
        DnsMessage.putU16(out, u, 5353);
        DnsMessage.putU16(out, u + 2, 53);
        DnsMessage.putU16(out, u + 4, IpPacket.UDP_HEADER_LEN + dns.length);
        System.arraycopy(dns, 0, out, u + 8, dns.length);

        IpPacket p = IpPacket.parse(out);
        assertNotNull(p);
        byte[] resp = p.buildResponse(p.dstIpBytes(), p.srcIpBytes(),
                DnsMessage.buildEmptyResponse(DnsMessage.parse(dns)), 0);
        assertNotNull(resp);
        IpPacket r = IpPacket.parse(resp);
        assertNotNull(r);
        assertFalse(r.isV4());
        assertEquals(53, r.srcPort());
        assertEquals(5353, r.dstPort());

        // IPv6 has no optional UDP checksum: a wrong one is a dead packet.
        int uu = IpPacket.IPV6_HEADER_LEN;
        int len = resp.length - uu;
        int sum = 0;
        byte[] s = p.dstIpBytes();
        byte[] d = p.srcIpBytes();
        for (int i = 0; i < 16; i += 2) {
            sum += ((s[i] & 0xFF) << 8) | (s[i + 1] & 0xFF);
            sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        }
        sum += IpPacket.PROTO_UDP;
        sum += len;
        assertEquals(0, IpPacket.checksum(resp, uu, len, sum),
                "IPv6 UDP checksum must re-verify to zero");
    }

    @Test
    void buildRefusesNullPayload() {
        IpPacket p = IpPacket.parse(v4Query(40000, 53,
                DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A)));
        assertNotNull(p);
        assertNull(p.buildResponse(p.dstIpBytes(), p.srcIpBytes(), null, 1));
    }

    /** RFC 1071 reference, written bit-by-bit and independent of the impl. */
    private static int referenceChecksum(byte[] b, int off, int len, int seed) {
        long sum = seed;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xFF;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) (~sum) & 0xFFFF;
    }

    @Test
    void checksumHandlesOddLength() {
        // 0x0102 + 0x0300 = 0x0402, complemented.
        assertEquals(0xFBFD, IpPacket.checksum(new byte[] {0x01, 0x02, 0x03}, 0, 3, 0));
        // Odd and even lengths, seeded, against the reference - including the
        // empty buffer, where the one's complement of zero is 0xFFFF.
        java.util.Random r = new java.util.Random(42);
        for (int n = 0; n < 41; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            assertEquals(referenceChecksum(b, 0, n, 0x1234),
                    IpPacket.checksum(b, 0, n, 0x1234), "length " + n);
        }
    }

    @Test
    void ipToTextFormatsV6() {
        byte[] ip6 = new byte[16];
        ip6[0] = 0x20; ip6[1] = 0x01; ip6[15] = (byte) 0x99;
        assertEquals("2001:0:0:0:0:0:0:99", IpPacket.toText(ip6));
        assertEquals("1.2.3.4", IpPacket.toText(new byte[] {1, 2, 3, 4}));
        assertEquals("?", IpPacket.toText(new byte[] {1}));
        assertEquals("?", IpPacket.toText(null));
    }

    @Test
    void nonDnsPortIsNotADnsQuery() {
        byte[] pkt = v4Query(40000, 443, DnsMessageTest.query(1, "example.com", DnsMessage.TYPE_A));
        IpPacket p = IpPacket.parse(pkt);
        assertNotNull(p);
        assertFalse(p.isDnsQuery());
    }
}
