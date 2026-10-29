package tracewall.data;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import tracewall.blocklist.HostListParser;

/** Shared bounded I/O and list validation; no Android or network dependency. */
public final class ListData {
    public static final int MAX_BYTES = 5_000_000;
    private ListData() {}
    public static byte[] read(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, cap);
        return output.toByteArray();
    }
    public static void copy(InputStream input, OutputStream output, int cap) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0, count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            if (count > cap - total) throw new IOException("Size cap exceeded");
            output.write(buffer, 0, count);
            total += count;
        }
    }
    public static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static Set<String> validate(String text, int minimum) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("List too large");
        HostListParser.Result result = HostListParser.parse(text);
        if (result.malformed() != 0 || result.entriesParsed() < minimum) throw new IllegalArgumentException("Invalid/short list");
        return result.set().patterns();
    }
    /** Replace the previous refresh while retaining the bundled baseline. */
    public static String merge(String bundled, String downloaded, String attribution) {
        Set<String> hosts = new TreeSet<>(validate(bundled, 1));
        hosts.addAll(validate(downloaded, 100));
        StringBuilder output = new StringBuilder("# ").append(attribution).append('\n');
        for (String host : hosts) output.append("0.0.0.0 ").append(host).append('\n');
        String text = output.toString();
        validate(text, 100);
        return text;
    }
    public static int added(String previous, String next) {
        Set<String> added = new HashSet<>(validate(next, 1));
        added.removeAll(validate(previous, 1));
        return added.size();
    }
}
