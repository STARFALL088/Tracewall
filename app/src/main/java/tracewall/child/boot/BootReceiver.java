package tracewall.child.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import tracewall.child.block.BlocklistStore;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.Notifications;
import tracewall.child.vpn.DnsVpnService;

/**
 * Boot receiver: the Week 10 BroadcastReceiver component, doing something that
 * is actually correct rather than something that demos well.
 *
 * It does not start the service. On Android 8+ a boot receiver cannot start a
 * background Service; on 12+ even a foreground start needs a declared type. The
 * tunnel comes back automatically only through always-on VPN, which the system
 * restarts for us - so if protection was wanted and is not running, the right
 * response is a notification, not a doomed startService().
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Tracewall";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (
            !Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
        ) {
            return;
        }

        boolean wanted = BlocklistStore.isEnabled(context);
        boolean running = DnsVpnService.isRunning();
        Log.i(TAG, "boot: wanted=" + wanted + " running=" + running);

        if (wanted && !running) {
            // Pre-warm Firebase so the anonymous UID is ready before the user
            // opens the app and the rules listener can start immediately.
            FirebaseSync.get().signInAnonymously(() ->
                Log.i(TAG, "Firebase pre-warmed after boot")
            );
            Notifications.showProtectionOff(context, true);
        }
    }
}
