package tracewall.auth;

import android.content.Context;
import android.content.SharedPreferences;

/** A device's cloud identity cannot be replaced by an unrelated successful login. */
public final class IdentityAnchor {
    private static final String PREFS = "tracewall.auth.identity";
    private static final String KEY_UID = "bound_uid";

    private IdentityAnchor() {}

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String get(Context context) {
        return preferences(context).getString(KEY_UID, null);
    }

    /** Bind once, synchronously, before starting any UID-scoped cloud publisher. */
    public static synchronized boolean bind(Context context, String uid) {
        if (uid == null || uid.isEmpty() || uid.length() > 128) return false;
        SharedPreferences prefs = preferences(context);
        String existing = prefs.getString(KEY_UID, null);
        if (existing != null) return existing.equals(uid);
        if (prefs.edit().putString(KEY_UID, uid).commit()) return true;
        // A failed commit may have changed SharedPreferences' in-memory map.
        // Do not expose an identity that was not durably anchored.
        prefs.edit().remove(KEY_UID).apply();
        return false;
    }

    public static synchronized boolean matches(Context context, String uid) {
        return uid != null && uid.equals(get(context));
    }
}
