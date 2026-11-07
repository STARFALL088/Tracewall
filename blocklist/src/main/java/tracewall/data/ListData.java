package tracewall.data;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import tracewall.blocklist.HostListParser;

/** Shared bounded I/O and list validation; no Android or network dependency. */
public final class ListData {
    public static final int MAX_BYTES = 5_000_000;
    public static final int MIN_FEED_HOSTS = 100;
    private ListData() {}
    public static byte[] read(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, cap);
        return output.toByteArray();
    }
    public static void copy(InputStream input, OutputStream output, int cap) throws IOException {
        if (cap < 0) throw new IllegalArgumentException("Negative cap");
        byte[] buffer = new byte[8192];
        int total = 0, count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            if (count > cap - total) throw new SizeException();
            output.write(buffer, 0, count);
            total += count;
        }
    }
    public static final class SizeException extends IOException {
        public SizeException() { super("Size cap exceeded"); }
    }
    public static final class TooLargeException extends IllegalArgumentException {
        public TooLargeException() { super("Assembled/raw list exceeds 5,000,000 bytes"); }
    }
    public static byte[] bytes(String text) {
        if (text == null) throw new IllegalArgumentException("Null list");
        if (text.length() > MAX_BYTES) throw new TooLargeException();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new TooLargeException();
        return bytes;
    }
    public static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static HostListParser.Result inspect(String text, int minimum) {
        if (minimum < 1) throw new IllegalArgumentException("Invalid minimum");
        bytes(text);
        // HostListParser intentionally tolerates bare-host trailing tokens and section headers.
        // A downloaded/cache replacement must not silently discard malformed content that way.
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            if (line.startsWith("[")) {
                if (!line.equals("[Adblock Plus 2.0]")) throw new IllegalArgumentException("Invalid list header");
                continue;
            }
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            String[] tokens = line.split("\\s+");
            boolean address = tokens[0].indexOf(':') >= 0 || tokens[0].matches("\\d{1,3}(\\.\\d{1,3}){3}");
            if (!address && tokens.length != 1) throw new IllegalArgumentException("Invalid bare-host row");
            if (address && !tokens[0].equals("0.0.0.0") && !tokens[0].equals("127.0.0.1")
                    && !tokens[0].equals("::1") && !tokens[0].equals("::")
                    && !tokens[0].equals("0:0:0:0:0:0:0:1")) {
                throw new IllegalArgumentException("Not a blocking hosts address");
            }
        }
        HostListParser.Result result = HostListParser.parse(text);
        if (result.malformed() != 0 || result.entriesParsed() < minimum) throw new IllegalArgumentException("Invalid/short list");
        return result;
    }
    /** Contract used by the policy loader: validates UTF-8 byte size and at least minimum unique hosts. */
    public static Set<String> validate(String text, int minimum) { return inspect(text, minimum).set().patterns(); }

    /** Replace previous downloads, retaining bundled seeds. Never unions previous downloaded hosts. */
    public static String merge(String bundled, String downloaded, String attribution) {
        Set<String> hosts = new TreeSet<>(validate(bundled, 1));
        hosts.addAll(validate(downloaded, MIN_FEED_HOSTS));
        return assemble(hosts, attribution);
    }
    /** Cap the normalized assembled output too, including header and per-host address expansion. */
    public static String assemble(Set<String> hosts, String attribution) {
        if (attribution == null || attribution.length() > 2048 || attribution.indexOf('\n') >= 0
                || attribution.indexOf('\r') >= 0 || attribution.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid attribution");
        }
        StringBuilder output = new StringBuilder("# ").append(attribution).append('\n');
        int size = output.toString().getBytes(StandardCharsets.UTF_8).length;
        for (String host : new TreeSet<>(hosts)) {
            int rowBytes = 9 + host.getBytes(StandardCharsets.UTF_8).length; // "0.0.0.0 " + host + LF
            if (rowBytes > MAX_BYTES - size) throw new TooLargeException();
            output.append("0.0.0.0 ").append(host).append('\n');
            size += rowBytes;
        }
        String text = output.toString();
        validate(text, MIN_FEED_HOSTS);
        return text;
    }
    public static int added(String previous, String next) { return difference(next, previous); }
    public static int removed(String previous, String next) { return difference(previous, next); }
    private static int difference(String left, String right) {
        Set<String> difference = new HashSet<>(validate(left, 1));
        difference.removeAll(validate(right, 1));
        return difference.size();
    }
}
