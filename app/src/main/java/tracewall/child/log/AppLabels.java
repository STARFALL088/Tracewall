package tracewall.child.log;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Map;

/**
 * uid -> human app name, cached.
 *
 * Called only from the UI thread: the lookups are cheap but not free, and the
 * packet path has no business touching PackageManager.
 */
public final class AppLabels {

    private final PackageManager pm;
    private final Map<Integer, String> cache = new HashMap<>();

    public AppLabels(PackageManager pm) {
        this.pm = pm;
    }

    public String label(int uid) {
        if (uid < 0) return "unknown";
        String cached = cache.get(uid);
        if (cached != null) return cached;

        String label = "uid " + uid;
        try {
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) {
                ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                CharSequence name = pm.getApplicationLabel(ai);
                label = name == null ? pkgs[0] : name.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // System uids (netd, root) have no package: the uid label stands.
        }
        cache.put(uid, label);
        return label;
    }
}
