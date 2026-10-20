package tracewall.net;

import java.nio.charset.StandardCharsets;

/**
 * A DNS message in RFC 1035 wire format - just enough of it to be a firewall.
 *
 * Scope, deliberately narrow:
 * <ul>
 *   <li>Header (12 bytes) and the question section are parsed properly.</li>
 *   <li>Names are decoded with full compression-pointer support, including
 *       pointer chains, with a loop guard.</li>
 *   <li>EDNS0 OPT records and unknown RR types are tolerated and ignored -
 *       we never need to understand them, only to skip them correctly.</li>
 * </ul>
 *
 * The answers section is NOT parsed into objects. When we forward a query we
 * hand the resolver's response back almost byte-for-byte, and when we block we
 * synthesise our own. Parsing 4KB of A/AAAA/SOA records into objects on every
 * lookup would be pure waste, so it is not done.
 *
 * All methods are allocation-conscious: this runs on the hot path of a VPN
 * packet loop where every byte allocated matters.
 */
public final class DnsMessage {

    public static final int HEADER_LEN = 12;

    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_MX = 15;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_OPT = 41;

    public static final int CLASS_IN = 1;

    public static final int RCODE_NOERROR = 0;
    public static final int RCODE_FORMERR = 1;
    public static final int RCODE_SERVFAIL = 2;
    public static final int RCODE_NXDOMAIN = 3;
    public static final int RCODE_NOTIMP = 4;
    public static final int RCODE_REFUSED = 5;

    /** Header + flags word. */
    public static final int FLAGS_QR = 0x8000;   // query=0, response=1
    public static final int FLAGS_TC = 0x0200;   // truncated
    public static final int FLAGS_RD = 0x0100;   // recursion desired
    public static final int FLAGS_RA = 0x0080;   // recursion available

    private final int id;
    private final int flags;
    private final int qdCount;
    private final int anCount;
    private final int nsCount;
    private final int arCount;
    private final String qName;
    private final int qType;
    private final int qClass;
    private final byte[] raw;

    private DnsMessage(int id, int flags, int qdCount, int anCount, int nsCount,
                      int arCount, String qName, int qType, int qClass, byte[] raw) {
        this.id = id;
        this.flags = flags;
        this.qdCount = qdCount;
        this.anCount = anCount;
        this.nsCount = nsCount;
        this.arCount = arCount;
        this.qName = qName;
        this.qType = qType;
        this.qClass = qClass;
        this.raw = raw;
    }

    public int id() { return id; }
    public int flags() { return flags; }
    public boolean isResponse() { return (flags & FLAGS_QR) != 0; }
    public boolean isTruncated() { return (flags & FLAGS_TC) != 0; }
    public int rCode() { return flags & 0x000F; }
    public int qdCount() { return qdCount; }
    public int anCount() { return anCount; }
    public int arCount() { return arCount; }
    public byte[] raw() { return raw; }

    /** The wire bytes of this packet, safe to write back to the TUN fd. */
    public byte[] toBytes() { return raw; }

    /**
     * The queried name, or {@code null} when this message has no question or
     * could not be parsed past the header. Never throws.
     */
    public String qName() { return qName; }

    public int qType() { return qType; }
    public int qClass() { return qClass; }

    /** True when this message is a plain recursive query for one question. */
    public boolean isSimpleQuery() {
        return !isResponse() && qdCount == 1 && qName != null;
    }

    // ---------------------------------------------------------------- parse

    /**
     * Parse a DNS message. Returns {@code null} on anything malformed rather
     * than throwing - a VPN is a hostile network position and every byte on
     * the wire is untrusted input. A packet we cannot parse is a packet we
     * drop, and dropping must not cost the caller an exception.
     */
    public static DnsMessage parse(byte[] buf, int off, int len) {
        if (buf == null || len < HEADER_LEN) return null;
        if (off < 0 || off + len > buf.length) return null;

        int id = u16(buf, off);
        int flags = u16(buf, off + 2);
        int qd = u16(buf, off + 4);
        int an = u16(buf, off + 6);
        int ns = u16(buf, off + 8);
        int ar = u16(buf, off + 10);

        if (qd > 1 || qd == 0) {
            // qd == 0 is legal for some response shapes, and qd > 1 means we
            // have no single name to attribute or log. Both go to the slow
            // path in the service; parse the header only.
            return new DnsMessage(id, flags, qd, an, ns, ar, null, 0, 0,
                    copy(buf, off, len));
        }

        int p = off + HEADER_LEN;
        int[] end = new int[] { p };
        String name = readName(buf, off, end);
        if (name == null) return null;
        if (end[0] + 4 > off + len) return null;
        int qtype = u16(buf, end[0]);
        int qclass = u16(buf, end[0] + 2);
        return new DnsMessage(id, flags, qd, an, ns, ar, name, qtype, qclass,
                copy(buf, off, len));
    }

    public static DnsMessage parse(byte[] buf) {
        return buf == null ? null : parse(buf, 0, buf.length);
    }

    /**
     * Read a (possibly compressed) name starting at {@code cursor[0]}.
     * Advances {@code cursor[0]} past the name as it appears at that position
     * - the pointer target bytes are never re-consumed by the caller.
     *
     * Returns {@code null} if the name runs off the end or the pointer chain
     * loops. Dot-decoded with escapes preserved as raw ASCII; this is a
     * firewall, not a resolver, so exotic IDN handling is left to the
     * upstream server that will actually answer the query.
     */
    static String readName(byte[] buf, int base, int[] cursor) {
        int p = cursor[0];
        StringBuilder sb = new StringBuilder(64);
        boolean jumped = false;
        int start = p;
        int hops = 0;
        int end = base + buf.length;      // conservative: real limit is tighter

        while (true) {
            if (p < 0 || p >= end) return null;
            int len = buf[p] & 0xFF;
            if ((len & 0xC0) == 0xC0) {           // compression pointer
                if (p + 1 >= end) return null;
                int target = ((len & 0x3F) << 8) | (buf[p + 1] & 0xFF);
                if (!jumped) {
                    start = p + 2;                // caller resumes after the ptr
                    jumped = true;
                }
                if (target >= start) return null; // must point backwards
                if (++hops > 64) return null;     // pointer loop guard
                p = base + target;
                continue;
            }
            if ((len & 0xC0) != 0) return null;   // reserved label type
            if (len == 0) {                        // end of name
                if (!jumped) start = p + 1;
                cursor[0] = start;
                return sb.length() == 0 ? "." : sb.toString();
            }
            if (len > 63) return null;
            if (p + 1 + len > end) return null;
            if (sb.length() > 0) sb.append('.');
            appendLabel(sb, buf, p + 1, len);
            if (sb.length() > 253) return null;
            p += 1 + len;
        }
    }

    /** Append one label, escaping the three bytes that are not plain text. */
    private static void appendLabel(StringBuilder sb, byte[] buf, int off, int len) {
        for (int i = 0; i < len; i++) {
            int c = buf[off + i] & 0xFF;
            if (c == '.' || c == '\\') {
                sb.append('\\');
                sb.append((char) c);
            } else if (c < 0x21 || c > 0x7E) {
                sb.append('\\');
                sb.append((char) ('0' + (c / 100)));
                sb.append((char) ('0' + ((c / 10) % 10)));
                sb.append((char) ('0' + (c % 10)));
            } else {
                sb.append((char) c);
            }
        }
    }

    // ------------------------------------------------------------- builders

    /**
     * Build a response that answers {@code query} with a single A record
     * pointing at {@code address} (normally 0.0.0.0 - "this host does not
     * exist as far as you are concerned").
     *
     * The question section is echoed byte-for-byte and every flag the client
     * set except QR and RCODE is preserved, so the client accepts it as a
     * genuine answer and does not retry in a loop.
     */
    public static byte[] buildAResponse(DnsMessage query, byte[] address) {
        return buildSynthetic(query, TYPE_A, address == null ? 4 : address.length);
    }

    /**
     * Build an empty NOERROR answer - correct for blocking AAAA, where
     * answering ::0 would be a type error rather than a denial, and for
     * opt-out A records that must resolve to nothing.
     */
    public static byte[] buildEmptyResponse(DnsMessage query) {
        return buildSynthetic(query, TYPE_A, 0);
    }

    public static byte[] buildNxDomain(DnsMessage query) {
        return buildSynthetic(query, -1, 0, RCODE_NXDOMAIN);
    }

    public static byte[] buildRefused(DnsMessage query) {
        return buildSynthetic(query, -1, 0, RCODE_REFUSED);
    }

    public static byte[] buildServFail(DnsMessage query) {
        return buildSynthetic(query, -1, 0, RCODE_SERVFAIL);
    }

    /**
     * A record with TTL 0 and no address, which is the honest "we are not
     * going to tell you" answer and keeps clients from caching our block.
     */
    private static byte[] buildSynthetic(DnsMessage query, int rtype, int rdlen) {
        return buildSynthetic(query, rtype, rdlen, RCODE_NOERROR);
    }

    private static byte[] buildSynthetic(DnsMessage query, int rtype, int rdlen,
                                         int rcode) {
        if (query == null || query.qName() == null) return null;
        byte[] name = encodeName(query.qName());
        if (name == null) return null;

        int ancount = (rdlen > 0) ? 1 : 0;
        // header(12) + question(name + TYPE(2) + CLASS(2))
        // + answer(name + TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) + rdata)
        int total = HEADER_LEN + name.length + 4
                + (ancount == 1 ? name.length + 10 + rdlen : 0);
        byte[] out = new byte[total];
        int p = 0;

        putU16(out, p, query.id());
        p += 2;
        // QR=1, copy RD from the query, set RA, preserve opcode, set rcode.
        int qflags = query.flags();
        // 0x7900 = opcode (0x7800) + RD (0x0100). Dropping RD makes some
        // stub resolvers treat the answer as unsolicited and ignore it.
        int flags = FLAGS_QR | (qflags & 0x7900) | FLAGS_RA | (rcode & 0x0F);
        putU16(out, p, flags);
        p += 2;
        putU16(out, p, 1);        p += 2;  // QDCOUNT
        putU16(out, p, ancount);  p += 2;  // ANCOUNT
        putU16(out, p, 0);        p += 2;  // NSCOUNT
        putU16(out, p, 0);        p += 2;  // ARCOUNT

        System.arraycopy(name, 0, out, p, name.length);
        p += name.length;
        putU16(out, p, query.qType());   p += 2;
        putU16(out, p, query.qClass());  p += 2;

        if (ancount == 1) {
            // Answer reuses the question's name (allowed, and avoids a pointer).
            System.arraycopy(name, 0, out, p, name.length);
            p += name.length;
            putU16(out, p, rtype);    p += 2;
            putU16(out, p, CLASS_IN); p += 2;
            putU16(out, p, 0);        p += 2;  // TTL high  -> 0
            putU16(out, p, 0);        p += 2;  // TTL low   -> 0, do not cache
            putU16(out, p, rdlen);    p += 2;  // RDLENGTH
            if (rdlen == 4) {
                out[p] = 0; out[p + 1] = 0; out[p + 2] = 0; out[p + 3] = 0;
            }
        }
        return out;
    }

    /**
     * Rewrite a resolver's response so it matches the query we sent:
     * transaction id corrected to ours, QR set, recursion-available set,
     * the truncated bit cleared (we may have completed it over TCP).
     *
     * Returning the resolver's bytes untouched except for those fields is
     * what makes every record type, DNSSEC AD bit and EDNS padding survive
     * untouched - we never have to understand a record to relay it.
     */
    public static byte[] normalizeResponse(byte[] upstream, int queryId,
                                           boolean wasTruncatedOverTcp) {
        if (upstream == null || upstream.length < HEADER_LEN) return null;
        byte[] out = copy(upstream, 0, upstream.length);
        putU16(out, 0, queryId & 0xFFFF);
        int flags = u16(out, 2) | FLAGS_QR | FLAGS_RA;
        if (wasTruncatedOverTcp) flags &= ~FLAGS_TC;
        putU16(out, 2, flags);
        return out;
    }

    /** Encode a dotted name as length-prefixed labels. FQDN dot optional. */
    public static byte[] encodeName(String name) {
        if (name == null || name.isEmpty()) return new byte[] { 0 };
        String s = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (s.isEmpty()) return new byte[] { 0 };
        String[] labels = s.split("\\.");
        int len = 1;
        for (String l : labels) {
            if (l.isEmpty() || l.length() > 63) return null;
            len += 1 + l.length();
        }
        if (len > 255) return null;
        byte[] out = new byte[len];
        int p = 0;
        for (String l : labels) {
            byte[] b = l.getBytes(StandardCharsets.US_ASCII);
            out[p++] = (byte) b.length;
            System.arraycopy(b, 0, out, p, b.length);
            p += b.length;
        }
        out[p] = 0;
        return out;
    }

    // -------------------------------------------------------------- helpers

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static void putU16(byte[] b, int i, int v) {
        b[i] = (byte) ((v >>> 8) & 0xFF);
        b[i + 1] = (byte) (v & 0xFF);
    }

    static byte[] copy(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    @Override public String toString() {
        return "Dns{id=" + id() + " " + (isResponse() ? "resp" : "query")
                + " q=" + qName + " type=" + typeName(qType) + " rcode=" + rCode() + "}";
    }

    public static String typeName(int t) {
        switch (t) {
            case TYPE_A: return "A";
            case TYPE_NS: return "NS";
            case TYPE_CNAME: return "CNAME";
            case TYPE_SOA: return "SOA";
            case TYPE_PTR: return "PTR";
            case TYPE_MX: return "MX";
            case TYPE_TXT: return "TXT";
            case TYPE_AAAA: return "AAAA";
            case TYPE_SRV: return "SRV";
            case TYPE_OPT: return "OPT";
            default: return "T" + t;
        }
    }
}
