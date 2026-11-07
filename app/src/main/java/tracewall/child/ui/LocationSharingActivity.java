package tracewall.child.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.text.DateFormat;
import java.util.Date;
import tracewall.child.R;
import tracewall.child.databinding.ActivityLocationSharingBinding;
import tracewall.child.location.LocationSharingService;
import tracewall.child.sync.FirebaseSync;

/** PIN-approved entry point for a visible, latest-only location foreground service. */
public final class LocationSharingActivity extends AppCompatActivity {
    private ActivityLocationSharingBinding ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean authorizedStart;
    private Integer idleStatus;

    private final ActivityResultLauncher<String[]> locationPermission = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allowed = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
            if (authorizedStart && allowed) requireVisibleNotification();
            else { authorizedStart = false; idleStatus = R.string.location_status_permission; render(); }
        });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), allowed -> {
            if (authorizedStart && allowed) startService();
            else { authorizedStart = false; idleStatus = R.string.location_status_permission; render(); }
        });

    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 2_000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityLocationSharingBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        ui.locationBack.setOnClickListener(v -> finish());
        ui.locationStart.setOnClickListener(v -> PinGate.require(this, this::prepareStart));
        ui.locationStop.setOnClickListener(v -> {
            LocationSharingService.stop(this);
            main.postDelayed(this::render, 300L);
        });
        ui.locationSettings.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            catch (android.content.ActivityNotFoundException missing) {
                Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show();
            }
        });
        render();
    }

    @Override protected void onStart() { super.onStart(); main.post(refresh); }
    @Override protected void onStop() { main.removeCallbacks(refresh); super.onStop(); }

    private void prepareStart() {
        FirebaseSync sync = FirebaseSync.get();
        if (!sync.isCloudEnabled() || sync.uid() == null) {
            authorizedStart = false;
            idleStatus = R.string.location_status_cloud;
            Toast.makeText(this, R.string.location_status_cloud, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        if (!locationEnabled()) {
            authorizedStart = false;
            idleStatus = R.string.location_status_disabled;
            Toast.makeText(this, R.string.location_status_disabled, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        authorizedStart = true;
        if (!hasLocationPermission()) {
            locationPermission.launch(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        } else requireVisibleNotification();
    }

    private void requireVisibleNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else startService();
    }

    private void startService() {
        if (!authorizedStart) return;
        authorizedStart = false;
        idleStatus = null;
        try { LocationSharingService.start(this); }
        catch (RuntimeException failure) {
            idleStatus = R.string.location_start_failed;
            Toast.makeText(this, R.string.location_start_failed, Toast.LENGTH_LONG).show();
        }
        main.postDelayed(this::render, 300L);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationEnabled() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void render() {
        boolean running = LocationSharingService.isRunning();
        String state = LocationSharingService.state(this);
        int status = "sharing".equals(state) ? R.string.location_status_active
            : "waiting".equals(state) ? R.string.location_status_waiting
            : "cloud_required".equals(state) ? R.string.location_status_cloud
            : "permission_required".equals(state) ? R.string.location_status_permission
            : "location_disabled".equals(state) ? R.string.location_status_disabled
            : "upload_failed".equals(state) ? R.string.location_status_failed
            : "invalid_fix".equals(state) ? R.string.location_status_invalid
            : R.string.location_status_off;
        ui.locationStatus.setText(!running && idleStatus != null ? idleStatus : status);
        long time = LocationSharingService.lastFixMs(this);
        ui.locationLastFix.setText(time <= 0 ? getString(R.string.location_last_never)
            : getString(R.string.location_last_fix,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)),
                LocationSharingService.lastAccuracyM(this)));
        ui.locationStart.setEnabled(!running);
        ui.locationStop.setEnabled(running);
    }
}
