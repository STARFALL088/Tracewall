package tracewall.auth;

import java.util.regex.Pattern;

/** Deterministic syntax checks only; Firebase remains the authority on credentials. */
public final class AuthInputs {
    private static final Pattern LOCAL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private AuthInputs() { }

    /** Surrounding whitespace is removed from identifiers, NEVER from passwords. */
    public static String email(String value) { return value == null ? "" : value.trim(); }

    public static boolean validEmail(String value) {
        String email = email(value);
        if (email.length() > 254) return false;
        int at = email.indexOf('@');
        if (at < 1 || at > 64 || at != email.lastIndexOf('@')) return false;
        String local = email.substring(0, at);
        if (!LOCAL.matcher(local).matches() || local.startsWith(".") || local.endsWith(".")
                || local.contains("..")) return false;
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) if (!DOMAIN_LABEL.matcher(label).matches()) return false;
        return true;
    }

    public static boolean validLoginPassword(String value) {
        return value != null && !value.isEmpty();
    }

    public static boolean validNewPassword(String value) {
        return value != null && value.codePointCount(0, value.length()) >= 8;
    }
}
