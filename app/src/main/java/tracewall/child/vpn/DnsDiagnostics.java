package tracewall.child.vpn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local diagnostics choices. Recording blocked SVCB/HTTPS probes is on by
 * default so encrypted-DNS discovery attempts are visible; the PIN-gated
 * switch can suppress those rows without stopping the counters or the block.
 */
public final class DnsDiagnostics {

    private static final String PREFS = "tracewall.diagnostics";
    private static final String LOG_HTTPS_PROBES = "log_https_probes";

    private DnsDiagnostics() {}

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean logHttpsProbes(Context context) {
        return preferences(context).getBoolean(LOG_HTTPS_PROBES, true);
    }

    public static boolean setLogHttpsProbes(Context context, boolean enabled) {
        return preferences(context).edit().putBoolean(LOG_HTTPS_PROBES, enabled).commit();
    }
}
