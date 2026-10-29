package tracewall.child;

import android.app.Application;
import android.util.Log;
import tracewall.child.block.BlocklistStore;
import tracewall.child.sync.FirebaseSync;
import tracewall.data.RemoteRules;

/**
 * Application subclass: the single startup hook that wires Firebase into the
 * rest of the app before any Activity or Service starts.
 *
 * Two things happen here and nowhere else:
 *
 *   1. Anonymous sign-in. This produces a stable UID that both the child and
 *      the parent app use to address the same Realtime Database node and
 *      Firestore documents. It must succeed before the rules listener is
 *      attached, which is why the listener is started inside the callback.
 *
 *   2. Rules listener. Once signed in, we subscribe to the parent's rule
 *      pushes for the lifetime of the process. We never detach this listener
 *      because the Application lives as long as the process does.
 *
 * Registered in AndroidManifest.xml via android:name=".TracewallApp".
 */
public class TracewallApp extends Application {

    private static final String TAG = "Tracewall";

    @Override
    public void onCreate() {
        super.onCreate();

        final android.content.Context ctx = getApplicationContext();
        FirebaseSync.get().initialize(ctx);
        if (BlocklistStore.isEnabled(ctx)) tracewall.child.net.ListRefreshWorker.schedule(ctx);

        FirebaseSync.get().signInAnonymously(() -> {
            Log.i(TAG, "Firebase ready, child uid=" + FirebaseSync.get().uid());

            FirebaseSync.get().watchPairing();
            FirebaseSync.get().refreshInstalledApps();

            // Process-lifetime subscription: the parent writes to
            // /devices/{uid}/rules and we apply it immediately.
            FirebaseSync.get().listenForRules(snapshot -> {
                try {
                    // An absent snapshot explicitly clears the remote keys. Local
                    // rules remain separate in BlocklistStore and are never erased.
                    RemoteRules rules = RemoteRules.parse(snapshot.getValue());
                    BlocklistStore.get().applyRemoteRules(ctx, rules.allow,
                        rules.blockedUids, rules.categories);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Rejected invalid remote rules; retaining last valid state", e);
                }
            });
        });
    }
}
