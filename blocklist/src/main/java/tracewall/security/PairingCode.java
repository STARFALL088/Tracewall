package tracewall.security;

import java.security.SecureRandom;
import java.util.Locale;

/** Versioned QR/manual payload; codes contain 40 random bits and expire server-side. */
public final class PairingCode {
    public static final long LIFETIME_MS = 5 * 60_000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public final String childUid;
    public final String code;

    public PairingCode(String childUid, String code) {
        if (childUid == null || !childUid.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid child UID");
        }
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-HJ-NP-Z2-9]{8}")) {
            throw new IllegalArgumentException("Invalid pairing code");
        }
        this.childUid = childUid;
        this.code = normalized;
    }

    public static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(ALPHABET.charAt(random.nextInt(32)));
        return out.toString();
    }

    public String payload() { return "tracewall:1:" + childUid + ":" + code; }

    public static PairingCode parse(String payload) {
        String[] parts = payload.trim().split(":", -1);
        if (parts.length != 4 || !"tracewall".equals(parts[0]) || !"1".equals(parts[1])) {
            throw new IllegalArgumentException("Invalid pairing payload");
        }
        return new PairingCode(parts[2], parts[3]);
    }
}
