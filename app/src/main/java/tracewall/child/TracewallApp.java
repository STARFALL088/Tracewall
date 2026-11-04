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
 *   1. Observe authentication. The account screen explicitly chooses named
 *      sign-in, a guest identity, or local use without an account. Background
 *      startup never silently creates or replaces an identity.
 *
 *   2. Rules listener. Once signed in, we subscribe to the parent's rule
 *      pushes for the lifetime of the process. We never detach this listener
 *      because the Application lives as long as the process does.
 *
 * Registered in AndroidManifest.xml via android:name=".TracewallApp".
 */
public class TracewallApp extends Application {

    private static final String TAG = "Tracewall";
    private String attachedUid;

    @Override
    public void onCreate() {
        super.onCreate();

        final android.content.Context ctx = getApplicationContext();
        FirebaseSync.get().initialize(ctx);
        if (BlocklistStore.isEnabled(ctx)) tracewall.child.net.ListRefreshWorker.schedule(ctx);

        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            com.google.firebase.auth.FirebaseUser user = auth.getCurrentUser();
            if (user == null || user.getUid().equals(attachedUid)) return;
            if (!tracewall.auth.IdentityAnchor.bind(ctx, user.getUid())) {
                Log.e(TAG, "Account does not match this protected device; cloud synchronization paused");
                return;
            }
            if (attachedUid != null) {
                // Child upgrades link credentials to the existing UID, never swap it.
                Log.e(TAG, "Child identity changed unexpectedly; retaining existing protection");
                return;
            }
            attachedUid = user.getUid();
            Log.i(TAG, "Firebase session ready; starting child synchronization");

            FirebaseSync.get().watchPairing();
            FirebaseSync.get().refreshInstalledApps();

            // Process-lifetime subscription: the parent writes to
            // /devices/{uid}/rules and we apply it immediately.
            FirebaseSync.get().listenForRules(snapshot -> {
                if (!attachedUid.equals(FirebaseSync.get().uid())) return;
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
