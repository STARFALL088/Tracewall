package tracewall.blocklist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * The hot path is deliberately allocation-light: lists contain hundreds of
 * thousands of lines and this runs on a phone. Each host is parsed once into a
 * {@link DomainPattern} and deduplicated by its normalized text, rather than
 * being validated as a string and then parsed a second time.
 */
public final class HostListParser {

    private static final int PARSED = 1;
    private static final int DUPLICATE = 2;
    private static final int MALFORMED = 3;
    private static final int SKIPPED = 4;

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
        // One parse per host; the normalized text is the dedup key.
        Map<String, DomainPattern> seen = new HashMap<>(1 << 16);

        int length = text.length();
        int at = 0;
        while (at <= length) {
            int eol = text.indexOf('\n', at);
            if (eol < 0) eol = length;
            String line = text.substring(at, eol).trim();
            at = eol + 1;
            lines++;
            if (line.isEmpty()) continue;
            if (line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) continue;

            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (line.isEmpty()) continue;

            int end = line.length();
            int cursor = 0;
            while (cursor < end && !isSpace(line.charAt(cursor))) cursor++;
            String first = line.substring(0, cursor);

            if (!looksLikeAddress(first)) {
                // Bare format: exactly one domain, regardless of later whitespace.
                int kind = add(seen, first);
                if (kind == PARSED) parsed++;
                else if (kind == DUPLICATE) duplicates++;
                else if (kind == MALFORMED) malformed++;
                continue;
            }

            // Hosts format: one address followed by zero or more aliases.
            int hosts = 0;
            while (true) {
                while (cursor < end && isSpace(line.charAt(cursor))) cursor++;
                int start = cursor;
                while (cursor < end && !isSpace(line.charAt(cursor))) cursor++;
                if (cursor == start) break;
                hosts++;
                int kind = add(seen, line.substring(start, cursor));
                if (kind == PARSED) parsed++;
                else if (kind == DUPLICATE) duplicates++;
                else if (kind == MALFORMED) malformed++;
            }
            if (hosts == 0) malformed++;
        }

        List<DomainPattern> list = new ArrayList<>(seen.size());
        list.addAll(seen.values());
        return new Result(new DomainSet(list), lines, parsed, malformed, duplicates);
    }

    /** @return one of PARSED/DUPLICATE/MALFORMED/SKIPPED. */
    private static int add(Map<String, DomainPattern> seen, String host) {
        if (host.equals("localhost") || host.equals("localhost.localdomain")
                || host.equals("ip6-localhost") || host.equals("ip6-loopback")) return SKIPPED;
        DomainPattern pattern = host.indexOf('.') < 0 || looksLikeAddress(host)
                ? null : DomainPattern.parse(host);
        if (pattern == null) return MALFORMED;
        if (seen.containsKey(pattern.text())) return DUPLICATE;
        seen.put(pattern.text(), pattern);
        return PARSED;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\f' || Character.isWhitespace(c);
    }

    /**
     * True for an IPv4 address or anything containing a colon (IPv6). Manual
     * rather than a regular expression because this runs once per token.
     */
    private static boolean looksLikeAddress(String token) {
        if (token.indexOf(':') >= 0) return true;
        int dots = 0;
        int digits = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.') {
                if (digits == 0) return false;
                dots++;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                digits++;
                if (digits > 3) return false;
            } else {
                return false;
            }
        }
        return dots == 3 && digits > 0;
    }
}
