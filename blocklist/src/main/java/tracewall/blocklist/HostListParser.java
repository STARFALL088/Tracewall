package tracewall.blocklist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parser for the "hosts file" blocklist format used by Firebog, hagezi, and
 * essentially every public list.
 *
 * Accepted line shapes:
 * <pre>
 *   0.0.0.0       ads.example.com      the normal case
 *   127.0.0.1     ads.example.com      same meaning to us
 *   ::1           ads.example.com      IPv6 loopback
 *   0:0:0:0:0:0:0:1  ads.example.com  IPv6 loopback, expanded
 *   ads.example.com                    bare host, some lists do this
 *   # comment                         skipped
 *   ! comment                         skipped (hagezi style)
 *   [Adblock Plus 2.0]                 skipped, section header
 * </pre>
 *
 * Rejects and counts, rather than silently dropping, so the app can show the
 * user "parsed 148,203 of 148,310 lines, 107 malformed" instead of lying.
 */
public final class HostListParser {

    private static final Pattern WS = Pattern.compile("\\s+");

    private HostListParser() { }

    public static final class Result {
        private final DomainSet set;
        private final int linesSeen;
        private final int entriesParsed;
        private final int malformed;
        private final int duplicates;

        Result(DomainSet set, int linesSeen, int entriesParsed,
               int malformed, int duplicates) {
            this.set = set;
            this.linesSeen = linesSeen;
            this.entriesParsed = entriesParsed;
            this.malformed = malformed;
            this.duplicates = duplicates;
        }

        public DomainSet set() { return set; }
        public int linesSeen() { return linesSeen; }
        public int entriesParsed() { return entriesParsed; }
        public int malformed() { return malformed; }
        public int duplicates() { return duplicates; }
    }

    public static Result parse(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(new DomainSet(), 0, 0, 0, 0);
        }

        int lines = 0;
        int parsed = 0;
        int malformed = 0;
        int duplicates = 0;
        // text is the dedup key; DomainSet re-splits into exact/wildcard halves.
        Set<String> seen = new HashSet<>(1 << 16);

        for (String rawLine : text.split("\n", -1)) {
            lines++;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) continue;

            String host = extractHost(line);
            if (host == null) { malformed++; continue; }
            // A blocklist entry with no dot is junk (or a local alias we cannot
            // route anyway), so it is counted as malformed rather than trusted.
            if (host.indexOf('.') < 0) { malformed++; continue; }

            DomainPattern pattern = DomainPattern.parse(host);
            if (pattern == null) { malformed++; continue; }
            if (!seen.add(pattern.text())) { duplicates++; continue; }
            parsed++;
        }

        List<DomainPattern> list = new ArrayList<>(seen.size());
        for (String s : seen) {
            DomainPattern p = DomainPattern.parse(s);
            if (p != null) list.add(p);
        }
        return new Result(new DomainSet(list), lines, parsed, malformed, duplicates);
    }

    /**
     * Strip an optional leading IP address and return the host. Returns null
     * when the line has no host-shaped token at all.
     *
     * Any address prefix counts - IPv4 dotted quad or IPv6 (which contains ':').
     * Matching on ':' rather than enumerating loopback spellings means
     * "::1 host" and "0:0:0:0:0:0:0:1 host" both work without special cases.
     */
    private static String extractHost(String line) {
        String first = null;
        String second = null;
        for (String p : WS.split(line)) {
            if (p.isEmpty()) continue;
            if (first == null) first = p;
            else if (second == null) { second = p; break; }
        }
        if (first == null) return null;

        // hosts format: <address> <host> [host...]
        if (second != null && looksLikeAddress(first)) return second;

        // Adblock syntax "address=/host/" is a different format - not ours.
        if (first.indexOf('/') >= 0) return null;
        return first;
    }

    private static boolean looksLikeAddress(String token) {
        return token.indexOf(':') >= 0 || IPV4.matcher(token).matches();
    }

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
}