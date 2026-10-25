package tracewall.parent;

import android.app.Application;
import android.util.Log;

import tracewall.parent.sync.FirebaseParent;

/**
 * Application subclass: signs in anonymously so the parent UID is ready
 * before PairingActivity or DashboardActivity starts.
 */
public class TracewallParentApp extends Application {

    private static final String TAG = "TracewallParent";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseParent.get().signInAnonymously(() ->
                Log.i(TAG, "Firebase ready, parent uid=" + FirebaseParent.get().uid()));
    }
}
