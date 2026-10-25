package tracewall.child;

import android.app.Application;
import android.util.Log;
import com.google.firebase.database.DataSnapshot;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import tracewall.child.block.BlocklistStore;
import tracewall.child.sync.FirebaseSync;

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

        FirebaseSync.get().signInAnonymously(() -> {
            Log.i(TAG, "Firebase ready, child uid=" + FirebaseSync.get().uid());

            // Process-lifetime subscription: the parent writes to
            // /devices/{uid}/rules and we apply it immediately.
            FirebaseSync.get().listenForRules(snapshot -> {
                if (!snapshot.exists()) return;

                // "allow" is stored as a Firebase list (integer-keyed object).
                Set<String> allow = new HashSet<>();
                for (DataSnapshot d : snapshot.child("allow").getChildren()) {
                    String host = d.getValue(String.class);
                    if (host != null && !host.isEmpty()) allow.add(
                        host.toLowerCase(Locale.ROOT)
                    );
                }

                // "blocked_uids" uses Long values because Firebase integers
                // always deserialise as Long on Android.
                Set<String> blockedUids = new HashSet<>();
                for (DataSnapshot d : snapshot
                    .child("blocked_uids")
                    .getChildren()) {
                    Long uid = d.getValue(Long.class);
                    if (uid != null) blockedUids.add(Long.toString(uid));
                }

                // "categories" is an object: {"malware":true, "ads":false, ...}.
                // Null means the parent has not set any categories remotely, so
                // the child keeps its on-device default set.
                Set<String> categories = null;
                DataSnapshot catSnap = snapshot.child("categories");
                if (catSnap.exists()) {
                    categories = new HashSet<>();
                    for (DataSnapshot d : catSnap.getChildren()) {
                        if (
                            Boolean.TRUE.equals(d.getValue(Boolean.class))
                        ) categories.add(d.getKey());
                    }
                }

                BlocklistStore.get().applyRemoteRules(
                    ctx,
                    allow,
                    blockedUids,
                    categories
                );
            });
        });
    }
}
