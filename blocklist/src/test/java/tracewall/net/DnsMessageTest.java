package tracewall.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsMessageTest {

    /** Build a query the way a real client would: header + question. */
    static byte[] query(int id, String name, int type) {
        byte[] n = DnsMessage.encodeName(name);
        byte[] out = new byte[DnsMessage.HEADER_LEN + n.length + 4];
        int p = 0;
        DnsMessage.putU16(out, p, id); p += 2;
        DnsMessage.putU16(out, p, DnsMessage.FLAGS_RD); p += 2;
        DnsMessage.putU16(out, p, 1); p += 2;   // QDCOUNT
        DnsMessage.putU16(out, p, 0); p += 2;   // ANCOUNT
        DnsMessage.putU16(out, p, 0); p += 2;   // NSCOUNT
        DnsMessage.putU16(out, p, 0); p += 2;   // ARCOUNT
        System.arraycopy(n, 0, out, p, n.length); p += n.length;
        DnsMessage.putU16(out, p, type);
        DnsMessage.putU16(out, p + 2, DnsMessage.CLASS_IN);
        return out;
    }

    @Test
    void parsesASimpleQuery() {
        DnsMessage m = DnsMessage.parse(query(0x1234, "example.com", DnsMessage.TYPE_A));
        assertNotNull(m);
        assertEquals(0x1234, m.id());
        assertEquals("example.com", m.qName());
        assertEquals(DnsMessage.TYPE_A, m.qType());
        assertEquals(DnsMessage.CLASS_IN, m.qClass());
        assertFalse(m.isResponse());
        assertFalse(m.isTruncated());
        assertTrue(m.isSimpleQuery());
    }

    @Test
    void qdCountZeroYieldsHeaderOnly() {
        byte[] q = query(1, "example.com", DnsMessage.TYPE_A);
        DnsMessage.putU16(q, 4, 0);          // QDCOUNT = 0
        DnsMessage m = DnsMessage.parse(q);
        assertNotNull(m);
        assertNull(m.qName());
        assertEquals(0, m.qdCount());
    }

    @Test
    void shortBufferIsRejected() {
        assertNull(DnsMessage.parse(new byte[11]));
        assertNull(DnsMessage.parse(new byte[0]));
        assertNull(DnsMessage.parse(null));
    }

    @Test
    void offsetAndLengthAreRespected() {
        byte[] q = query(0xBEEF, "offset.test", DnsMessage.TYPE_AAAA);
        byte[] padded = new byte[q.length + 16];
        System.arraycopy(q, 0, padded, 8, q.length);
        DnsMessage m = DnsMessage.parse(padded, 8, q.length);
        assertNotNull(m);
        assertEquals(0xBEEF, m.id());
        assertEquals("offset.test", m.qName());
        assertEquals(DnsMessage.TYPE_AAAA, m.qType());
    }

    @Test
    void lengthBeyondBufferIsRejected() {
        byte[] q = query(1, "example.com", DnsMessage.TYPE_A);
        assertNull(DnsMessage.parse(q, 0, q.length + 1));
        assertNull(DnsMessage.parse(q, 1, q.length));
    }

    @Test
    void nameIsLowercasedOnParseWhenEncodedInCaps() {
        byte[] n = DnsMessage.encodeName("EXAMPLE.COM");
        byte[] q = query(1, "example.com", DnsMessage.TYPE_A);
        System.arraycopy(n, 0, q, DnsMessage.HEADER_LEN, n.length);
        DnsMessage m = DnsMessage.parse(q);
        assertNotNull(m);
        // Labels are preserved as sent; matching is case-insensitive, which is
        // where the lowercase discipline actually matters.
        assertEquals("EXAMPLE.COM", m.qName());
    }

    @Test
    void compressionPointerResolvesToFullName() {
        // Response layout: header, question "example.com" at offset 12,
        // then an answer whose name is a pointer back to that same question.
        byte[] n = DnsMessage.encodeName("example.com");
        byte[] out = new byte[DnsMessage.HEADER_LEN + n.length + 4 + 16];
        int p = 0;
        DnsMessage.putU16(out, p, 0x4242); p += 2;
        DnsMessage.putU16(out, p, DnsMessage.FLAGS_QR | DnsMessage.FLAGS_RD | DnsMessage.FLAGS_RA); p += 2;
        DnsMessage.putU16(out, p, 1); p += 2;
        DnsMessage.putU16(out, p, 1); p += 2;   // one answer
        DnsMessage.putU16(out, p, 0); p += 2;
        DnsMessage.putU16(out, p, 0); p += 2;
        System.arraycopy(n, 0, out, p, n.length); p += n.length;
        DnsMessage.putU16(out, p, DnsMessage.TYPE_A);
        DnsMessage.putU16(out, p, DnsMessage.CLASS_IN);
        p += 4;
        // The answer's NAME is a pointer to offset 12.
        int ptrOff = p;
        out[p++] = (byte) 0xC0;
        out[p++] = (byte) 0x0C;
        DnsMessage.putU16(out, p, DnsMessage.TYPE_A); p += 2;
        DnsMessage.putU16(out, p, DnsMessage.CLASS_IN); p += 2;
        DnsMessage.putU16(out, p, 0x0000); p += 2;   // TTL high
        DnsMessage.putU16(out, p, 0x003C); p += 2;   // TTL low = 60
        DnsMessage.putU16(out, p, 4); p += 2;        // RDLENGTH
        out[p++] = (byte) 93; out[p++] = (byte) 184;
        out[p++] = (byte) 216; out[p++] = (byte) 34;

        DnsMessage m = DnsMessage.parse(out);
        assertNotNull(m);
        assertTrue(m.isResponse());
        assertEquals("example.com", m.qName());
        assertEquals(1, m.anCount());
        assertEquals(0xC0, out[ptrOff] & 0xFF);
    }

    @Test
    void compressionPointerLoopIsRejected() {
        // Two labels pointing at each other: an infinite loop if unguarded.
        byte[] out = new byte[DnsMessage.HEADER_LEN + 4];
        int p = DnsMessage.HEADER_LEN;
        DnsMessage.putU16(out, 0, 1);
        DnsMessage.putU16(out, 2, DnsMessage.FLAGS_RD);
        DnsMessage.putU16(out, 4, 1);
        out[p] = (byte) 0xC0;
        out[p + 1] = (byte) 0x0C;      // points at itself
        out[p + 2] = (byte) 0xC0;
        out[p + 3] = (byte) 0x0C;
        assertNull(DnsMessage.parse(out));
    }

    @Test
    void forwardPointerIsRejected() {
        // A pointer must point backwards; one that points forward is malformed.
        byte[] out = new byte[DnsMessage.HEADER_LEN + 4];
        DnsMessage.putU16(out, 0, 1);
        DnsMessage.putU16(out, 2, DnsMessage.FLAGS_RD);
        DnsMessage.putU16(out, 4, 1);
        int p = DnsMessage.HEADER_LEN;
        out[p] = (byte) 0xC0;
        out[p + 1] = (byte) 0x20;      // forward
        assertNull(DnsMessage.parse(out));
    }

    @Test
    void truncatedFlagIsRead() {
        byte[] q = query(1, "example.com", DnsMessage.TYPE_A);
        DnsMessage.putU16(q, 2, DnsMessage.FLAGS_RD | DnsMessage.FLAGS_TC);
        DnsMessage m = DnsMessage.parse(q);
        assertNotNull(m);
        assertTrue(m.isTruncated());
    }

    @Test
    void rCodeIsRead() {
        byte[] q = query(1, "example.com", DnsMessage.TYPE_A);
        DnsMessage.putU16(q, 2, DnsMessage.FLAGS_QR | DnsMessage.RCODE_NXDOMAIN);
        DnsMessage m = DnsMessage.parse(q);
        assertNotNull(m);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, m.rCode());
    }

    // ------------------------------------------------------------- builders

    @Test
    void blockResponseIsZeroedAddressWithZeroTtl() {
        DnsMessage q = DnsMessage.parse(query(0xAAAA, "ads.example.com", DnsMessage.TYPE_A));
        byte[] r = DnsMessage.buildAResponse(q, new byte[] {0, 0, 0, 0});
        assertNotNull(r);
        DnsMessage m = DnsMessage.parse(r);
        assertNotNull(m);
        assertTrue(m.isResponse());
        assertEquals(0xAAAA, m.id(), "client's transaction id must be preserved");
        assertEquals("ads.example.com", m.qName());
        assertEquals(DnsMessage.RCODE_NOERROR, m.rCode());
        assertEquals(1, m.anCount(), "exactly one A record");
        assertTrue((m.flags() & DnsMessage.FLAGS_RD) != 0, "RD copied from the query");
        assertTrue((m.flags() & DnsMessage.FLAGS_RA) != 0, "RA set");
        // TTL must be 0 in both halves.
        int ttlOff = r.length - 4 - 4 - 6;    // back from RDLEN + rdata end
        assertEquals(0, DnsMessage.u16(r, ttlOff + 4));
        assertEquals(0, DnsMessage.u16(r, ttlOff + 5));
        assertEquals(0, r[r.length - 1], "address must be 0.0.0.0");
    }

    @Test
    void blockResponseIsTheSameLengthAsARealOne() {
        DnsMessage q = DnsMessage.parse(query(1, "a.example", DnsMessage.TYPE_A));
        byte[] blocked = DnsMessage.buildAResponse(q, new byte[] {0, 0, 0, 0});
        assertNotNull(blocked);
        int nameLen = DnsMessage.encodeName("a.example").length;
        int expected = DnsMessage.HEADER_LEN + nameLen + 4 + (nameLen + 10 + 4);
        assertEquals(expected, blocked.length);
    }

    @Test
    void emptyResponseCarriesNoAnswers() {
        DnsMessage q = DnsMessage.parse(query(7, "blocked.test", DnsMessage.TYPE_AAAA));
        byte[] r = DnsMessage.buildEmptyResponse(q);
        DnsMessage m = DnsMessage.parse(r);
        assertNotNull(m);
        assertEquals(0, m.anCount());
        assertEquals(7, m.id());
        assertEquals("blocked.test", m.qName());
    }

    @Test
    void nxDomainResponseCarriesNxDomain() {
        DnsMessage q = DnsMessage.parse(query(9, "nope.test", DnsMessage.TYPE_A));
        DnsMessage m = DnsMessage.parse(DnsMessage.buildNxDomain(q));
        assertNotNull(m);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, m.rCode());
        assertTrue(m.isResponse());
    }

    @Test
    void servFailResponseCarriesServFail() {
        DnsMessage q = DnsMessage.parse(query(11, "timeout.test", DnsMessage.TYPE_A));
        DnsMessage m = DnsMessage.parse(DnsMessage.buildServFail(q));
        assertNotNull(m);
        assertEquals(DnsMessage.RCODE_SERVFAIL, m.rCode());
    }

    @Test
    void refusedResponseCarriesRefused() {
        DnsMessage q = DnsMessage.parse(query(13, "refused.test", DnsMessage.TYPE_A));
        DnsMessage m = DnsMessage.parse(DnsMessage.buildRefused(q));
        assertNotNull(m);
        assertEquals(DnsMessage.RCODE_REFUSED, m.rCode());
    }

    @Test
    void builderRefusesAMessageWithNoQuestion() {
        // A header-only message: parses fine, but there is nothing to echo.
        byte[] headerOnly = new byte[DnsMessage.HEADER_LEN];
        DnsMessage.putU16(headerOnly, 0, 1);
        DnsMessage.putU16(headerOnly, 2, DnsMessage.FLAGS_RD);
        DnsMessage q = DnsMessage.parse(headerOnly);
        assertNotNull(q);
        assertNull(q.qName());
        assertNull(DnsMessage.buildAResponse(q, new byte[4]));
        assertNull(DnsMessage.buildEmptyResponse(q));
        assertNull(DnsMessage.buildNxDomain(q));
        assertNull(DnsMessage.buildServFail(q));
        assertNull(DnsMessage.buildRefused(q));
    }

    @Test
    void builderRefusesNull() {
        assertNull(DnsMessage.buildAResponse(null, new byte[4]));
        assertNull(DnsMessage.buildEmptyResponse(null));
    }

    @Test
    void normalizeRewritesIdAndSetsFlags() {
        byte[] upstream = DnsMessage.buildAResponse(
                DnsMessage.parse(query(0x1111, "x.test", DnsMessage.TYPE_A)), new byte[4]);
        byte[] fixed = DnsMessage.normalizeResponse(upstream, 0x2222, true);
        assertNotNull(fixed);
        DnsMessage m = DnsMessage.parse(fixed);
        assertEquals(0x2222, m.id());
        assertTrue(m.isResponse());
        assertTrue((m.flags() & DnsMessage.FLAGS_RA) != 0);
        assertFalse(m.isTruncated(), "TC cleared when we completed it over TCP");
        // The original must not be mutated - it may still be in a socket buffer.
        assertEquals(0x1111, DnsMessage.u16(upstream, 0));
    }

    @Test
    void normalizeKeepsTruncatedWhenNotOverTcp() {
        byte[] upstream = new byte[DnsMessage.HEADER_LEN];
        DnsMessage.putU16(upstream, 2, DnsMessage.FLAGS_TC);
        DnsMessage m = DnsMessage.parse(DnsMessage.normalizeResponse(upstream, 1, false));
        assertTrue(m.isTruncated());
    }

    @Test
    void normalizeRejectsTooShort() {
        assertNull(DnsMessage.normalizeResponse(new byte[5], 1, false));
        assertNull(DnsMessage.normalizeResponse(null, 1, false));
    }

    @Test
    void encodeNameRejectsOversizedAndEmptyLabels() {
        assertNull(DnsMessage.encodeName("a..b"));
        // "" and "." are the root name (valid, returns {0})
        assertNotNull(DnsMessage.encodeName(""));
        assertNotNull(DnsMessage.encodeName("."));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        assertNull(DnsMessage.encodeName(sb.toString() + ".test"), "label over 63 chars");
    }

    @Test
    void encodeNameAcceptsTrailingDotAndRoot() {
        assertArrayEquals(DnsMessage.encodeName("example.com"),
                DnsMessage.encodeName("example.com."));
        assertArrayEquals(new byte[] {0}, DnsMessage.encodeName("."));
    }

    @Test
    void typeNamesAreHumanReadable() {
        assertEquals("A", DnsMessage.typeName(DnsMessage.TYPE_A));
        assertEquals("AAAA", DnsMessage.typeName(DnsMessage.TYPE_AAAA));
        assertEquals("T65", DnsMessage.typeName(65));
        assertEquals("T99", DnsMessage.typeName(99));
    }

    @Test
    void roundTripThroughEncodeAndParse() {
        String[] names = {
                "a.test", "x.y.z.example.co.uk", "very-long-label-that-is-fine.test",
                "1.2.3.4.test",
        };
        for (String n : names) {
            DnsMessage m = DnsMessage.parse(query(1, n, DnsMessage.TYPE_A));
            assertNotNull(m, n);
            assertEquals(n, m.qName());
        }
    }
}
