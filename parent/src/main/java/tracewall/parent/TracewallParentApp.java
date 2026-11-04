package tracewall.parent;

import android.app.Application;
import android.util.Log;

import tracewall.parent.sync.FirebaseParent;

/**
 * Observes the selected account without silently replacing a signed-out
 * session with another anonymous identity.
 */
public class TracewallParentApp extends Application {

    private static final String TAG = "TracewallParent";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseParent.get().initialize(this);
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            if (auth.getCurrentUser() != null
                    && tracewall.auth.IdentityAnchor.bind(this, auth.getCurrentUser().getUid()))
                Log.i(TAG, "Firebase session ready");
        });
    }
}
