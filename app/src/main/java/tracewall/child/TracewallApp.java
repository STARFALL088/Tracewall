package tracewall.child;

import android.app.Application;
import android.util.Log;
import tracewall.child.block.BlocklistStore;
import tracewall.child.log.HistoryWriter;
import tracewall.child.sync.FirebaseSync;
import tracewall.data.RemoteRules;

/**
 * Application subclass: local filtering always starts warm; cloud work only
 * runs after a PIN-approved cloud opt-in and an authenticated, bound identity.
 *
 * <p>The account screen chooses the identity. This class never creates an
 * anonymous or replacement user in the background. A rule listener is attached
 * only while cloud mode is enabled and the same identity remains bound.
 *
 * <p>Registered in AndroidManifest.xml via android:name=".TracewallApp".
 */
public class TracewallApp extends Application {

    private static final String TAG = "Tracewall";
    private String attachedUid;

    @Override
    public void onCreate() {
        super.onCreate();

        final android.content.Context ctx = getApplicationContext();
        FirebaseSync sync = FirebaseSync.get();
        sync.initialize(ctx);
        if (BlocklistStore.isEnabled(ctx)) tracewall.child.net.ListRefreshWorker.schedule(ctx);
        // Prune/no-persistence also applies when the VPN is not currently running.
        HistoryWriter.get().start(ctx);
        // Parse the category lists off the main thread. First paint, the rules
        // listener and the VPN toggle then reuse the warm policy instead of
        // blocking the UI for the duration of a cold parse.
        BlocklistStore.get().preload(ctx);

        // Establish the already-authenticated identity before the first
        // reconfigure so startup does not attach the same listeners twice.
        com.google.firebase.auth.FirebaseUser existing =
            com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (existing != null && tracewall.auth.IdentityAnchor.bind(ctx, existing.getUid()))
            attachedUid = existing.getUid();
        else if (existing != null)
            Log.e(TAG, "Account does not match this protected device; cloud synchronization paused");

        // Process-lifetime rule handler; FirebaseSync attaches/detaches the
        // actual listener when cloud mode and the bound identity permit it.
        sync.start(snapshot -> {
            if (attachedUid == null || !attachedUid.equals(sync.uid())) return;
            try {
                // An absent snapshot explicitly clears the remote keys. Local
                // rules remain separate in BlocklistStore and are never erased.
                RemoteRules rules = RemoteRules.parse(snapshot.getValue());
                BlocklistStore.get().applyRemoteRulesAsync(ctx, rules.allow,
                    rules.blockedUids, rules.categories);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Rejected invalid remote rules; retaining last valid state", e);
            }
        });

        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            com.google.firebase.auth.FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                attachedUid = null;
                sync.onIdentityLost();
                return;
            }
            if (user.getUid().equals(attachedUid)) return;
            if (!tracewall.auth.IdentityAnchor.bind(ctx, user.getUid())) {
                Log.e(TAG, "Account does not match this protected device; cloud synchronization paused");
                sync.onIdentityLost();
                return;
            }
            if (attachedUid != null) {
                // Child upgrades link credentials to the existing UID, never swap it.
                Log.e(TAG, "Child identity changed unexpectedly; retaining existing protection");
                return;
            }
            attachedUid = user.getUid();
            Log.i(TAG, "Firebase session ready; starting child synchronization");
            sync.onIdentityReady();
        });
    }
}
