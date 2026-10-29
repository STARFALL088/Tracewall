package tracewall.parent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import tracewall.data.DeviceProfiles;
import tracewall.security.PairingCode;

/** Parent-local names and selection. Server membership is always checked separately. */
final class ParentDevices {
    private static final String KEY_PROFILES = "device_profiles_v1";
    private ParentDevices() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PairingActivity.PREFS, Context.MODE_PRIVATE);
    }

    static DeviceProfiles all(Context context) {
        SharedPreferences preferences = prefs(context);
        DeviceProfiles profiles;
        try { profiles = DeviceProfiles.parse(preferences.getString(KEY_PROFILES, null)); }
        catch (IllegalArgumentException error) {
            Log.w("TracewallParent", "Invalid local device names; selected UID can still be recovered", error);
            profiles = DeviceProfiles.parse(null);
        }
        String legacy = preferences.getString(PairingActivity.KEY_CHILD_UID, null);
        if (PairingCode.validChildUid(legacy) && !profiles.names().containsKey(legacy)
                && profiles.names().size() < DeviceProfiles.MAX_DEVICES) {
            profiles = profiles.put(legacy, context.getString(R.string.device_default_name));
            preferences.edit().putString(KEY_PROFILES, profiles.serialize()).apply();
        }
        return profiles;
    }

    static void remember(Context context, String uid, String name) {
        String display = name == null || name.trim().isEmpty() ? context.getString(R.string.device_default_name) : name.trim();
        DeviceProfiles profiles = all(context).put(uid, display);
        prefs(context).edit().putString(KEY_PROFILES, profiles.serialize())
            .putString(PairingActivity.KEY_CHILD_UID, uid).apply();
    }

    static void forget(Context context, String uid) {
        DeviceProfiles profiles = all(context).remove(uid);
        SharedPreferences.Editor edit = prefs(context).edit().putString(KEY_PROFILES, profiles.serialize());
        if (uid.equals(prefs(context).getString(PairingActivity.KEY_CHILD_UID, "")))
            edit.remove(PairingActivity.KEY_CHILD_UID);
        edit.apply();
    }

    static String name(Context context, String uid) {
        String name = all(context).names().get(uid);
        return name == null ? uid : name;
    }
    static void select(Context context, String uid) {
        // A slow access check must not overwrite a newer local rename or resurrect a forgotten profile.
        if (!all(context).names().containsKey(uid)) throw new IllegalArgumentException("Device was forgotten");
        prefs(context).edit().putString(PairingActivity.KEY_CHILD_UID, uid).apply();
    }
}
