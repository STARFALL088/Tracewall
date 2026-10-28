package tracewall.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Persistent PIN verifier; storage and clock are supplied by the caller. */
public final class PinStore {
    // SHA-1 PBKDF2 is available on every supported Android API (including 24).
    private static final int ITERATIONS = 210_000;
    public static final long LOCKOUT_MS = 30_000;
    public interface Storage {
        String get(String key);
        void put(String key, String value);
    }
    private final Storage storage;

    public PinStore(Storage storage) { this.storage = storage; }

    public synchronized boolean isSet() { return storage.get("verifier") != null; }

    public static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4,6}");
    }

    public synchronized void set(String pin) {
        if (isSet()) throw new IllegalStateException("PIN already set");
        if (!validPin(pin)) throw new IllegalArgumentException("4-6 digits required");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        storage.put("verifier", hex(salt) + ":" + hex(derive(pin, salt)));
        reset();
    }

    public synchronized long lockedFor(long nowMs) {
        return Math.max(0, number("locked_until") - nowMs);
    }

    public synchronized boolean verify(String pin, long nowMs) {
        if (!isSet() || lockedFor(nowMs) > 0) return false;
        String[] parts = storage.get("verifier").split(":");
        boolean matches = validPin(pin) && MessageDigest.isEqual(
            unhex(parts[1]), derive(pin, unhex(parts[0])));
        if (matches) {
            reset();
            return true;
        }
        long attempts = number("attempts") + 1;
        if (attempts >= 5) {
            storage.put("locked_until", Long.toString(nowMs + LOCKOUT_MS));
            attempts = 0;
        }
        storage.put("attempts", Long.toString(attempts));
        return false;
    }

    private long number(String key) {
        String value = storage.get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void reset() {
        storage.put("attempts", "0");
        storage.put("locked_until", "0");
    }

    private static byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static byte[] unhex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
