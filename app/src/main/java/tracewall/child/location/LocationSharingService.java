package tracewall.child.location;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.text.DateFormat;
import java.util.Date;
import tracewall.child.R;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.LocationSharingActivity;
import tracewall.data.LocationSnapshot;

/** Visible, latest-only child location sharing; never starts silently or at boot. */
public final class LocationSharingService extends Service implements LocationListener {
    public static final String ACTION_START = "tracewall.child.location.START";
    public static final String ACTION_STOP = "tracewall.child.location.STOP";
    private static final String CHANNEL_ID = "tracewall.location";
    private static final int NOTIFICATION_ID = 4;
    private static final long MIN_TIME_MS = 30_000L;
    private static final float MIN_DISTANCE_M = 25.0f;
    private static final long GUARD_INTERVAL_MS = 15_000L;
    private static final String PREFS = "tracewall.location";
    private static final String KEY_STATE = "state";
    private static final String KEY_LAST_TIME = "last_time_ms";
    private static final String KEY_LAST_ACCURACY = "last_accuracy_m";
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private Location lastPublished;
    private long lastPublishedElapsed;
    private boolean stopping;

    private final Runnable guard = new Runnable() {
        @Override public void run() {
            FirebaseSync sync = FirebaseSync.get();
            if (!sync.isCloudEnabled() || sync.uid() == null) {
                setState("cloud_required");
                stopTracking(true);
                return;
            }
            main.postDelayed(this, GUARD_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        ContextCompat.startForegroundService(context,
            new Intent(context, LocationSharingService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, LocationSharingService.class).setAction(ACTION_STOP));
    }

    public static boolean isRunning() { return running; }
    public static String state(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, "off");
    }
    public static long lastFixMs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_TIME, 0L);
    }
    public static float lastAccuracyM(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_LAST_ACCURACY, 0f);
    }

    @Override public void onCreate() {
        super.onCreate();
        locationManager = getSystemService(LocationManager.class);
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        startForegroundVisible(notification(getString(R.string.location_notification_waiting)));
        FirebaseSync sync = FirebaseSync.get();
        if (!hasPermission() || !sync.isCloudEnabled() || sync.uid() == null) {
            setState(!hasPermission() ? "permission_required" : "cloud_required");
            stopTracking(false);
            return START_NOT_STICKY;
        }
        if (!startUpdates()) {
            setState("location_disabled");
            stopTracking(false);
            return START_NOT_STICKY;
        }
        running = true;
        stopping = false;
        setState("waiting");
        main.removeCallbacks(guard);
        main.postDelayed(guard, GUARD_INTERVAL_MS);
        seedRecentLocation();
        return START_NOT_STICKY;
    }

    private void startForegroundVisible(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressWarnings("MissingPermission")
    private boolean startUpdates() {
        if (locationManager == null) return false;
        return requestProvider(LocationManager.GPS_PROVIDER)
            | requestProvider(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressWarnings("MissingPermission")
    private boolean requestProvider(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) return false;
            locationManager.requestLocationUpdates(provider,
                MIN_TIME_MS, 0f, this, Looper.getMainLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException unavailable) { return false; }
    }

    @SuppressWarnings("MissingPermission")
    private void seedRecentLocation() {
        if (locationManager == null) return;
        Location best = null;
        try {
            for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && System.currentTimeMillis() - candidate.getTime() <= 120_000L
                        && (best == null || candidate.getAccuracy() < best.getAccuracy())) best = candidate;
            }
        } catch (SecurityException ignored) { return; }
        if (best != null) publish(best);
    }

    @Override public void onLocationChanged(@NonNull Location location) { publish(location); }

    private void publish(Location location) {
        if (stopping) return;
        String provider = location.getProvider();
        if (!LocationManager.GPS_PROVIDER.equals(provider)
                && !LocationManager.NETWORK_PROVIDER.equals(provider)) return;
        long elapsed = SystemClock.elapsedRealtime();
        if (lastPublished != null && elapsed - lastPublishedElapsed < MIN_TIME_MS
                && lastPublished.distanceTo(location) < MIN_DISTANCE_M) return;
        long now = System.currentTimeMillis();
        long time = location.getTime();
        if (time <= 0 || time > now + 5 * 60_000L) time = now;
        float accuracy = location.hasAccuracy() ? Math.max(0f, location.getAccuracy()) : 0f;
        boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        LocationSnapshot snapshot;
        try {
            snapshot = new LocationSnapshot(location.getLatitude(), location.getLongitude(), accuracy,
                time, provider, mock);
        } catch (IllegalArgumentException invalid) {
            setState("invalid_fix");
            return;
        }
        if (!FirebaseSync.get().publishLocation(snapshot, () -> {
            lastPublished = new Location(location);
            lastPublishedElapsed = elapsed;
            preferences().edit().putString(KEY_STATE, "sharing")
                .putLong(KEY_LAST_TIME, snapshot.timeMs).putFloat(KEY_LAST_ACCURACY, accuracy).apply();
            updateNotification(getString(R.string.location_notification_active,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(snapshot.timeMs))));
        }, () -> setState("upload_failed"))) {
            setState("cloud_required");
            stopTracking(true);
        }
    }

    private void stopTracking(boolean clearRemote) {
        if (stopping) return;
        stopping = true;
        running = false;
        main.removeCallbacks(guard);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (clearRemote) FirebaseSync.get().clearLocation(() -> {});
        if (!"cloud_required".equals(state(this)) && !"permission_required".equals(state(this))
                && !"location_disabled".equals(state(this))) setState("off");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences preferences() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void setState(String state) { preferences().edit().putString(KEY_STATE, state).apply(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.location_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.location_notification_channel_body));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 40,
            new Intent(this, LocationSharingActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 41,
            new Intent(this, LocationSharingService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(text).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,
                getString(R.string.location_stop), stop).build())
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
            .setOnlyAlertOnce(true).setShowWhen(false).build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onProviderDisabled(@NonNull String provider) {
        setState("waiting");
        updateNotification(getString(R.string.location_notification_waiting));
    }
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onDestroy() {
        main.removeCallbacks(guard);
        if (locationManager != null) locationManager.removeUpdates(this);
        running = false;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
