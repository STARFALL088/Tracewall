package tracewall.parent;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.DateFormat;
import java.util.Date;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import tracewall.data.LocationSnapshot;
import tracewall.parent.databinding.ActivityLocationBinding;
import tracewall.parent.sync.FirebaseParent;

/** Verified parent map for the child's latest shared point; no route trail is retained. */
public final class LocationActivity extends AppCompatActivity {
    private ActivityLocationBinding ui;
    private ListenerRegistration registration;
    private String childUid;
    private Marker marker;
    private GeoPoint latestPoint;
    private boolean centered;
    private boolean started;
    private int connection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        ui = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        ui.locationToolbar.setNavigationOnClickListener(v -> finish());
        childUid = getSharedPreferences(PairingActivity.PREFS, MODE_PRIVATE)
            .getString(PairingActivity.KEY_CHILD_UID, "");

        ui.locationMap.setTileSource(TileSourceFactory.MAPNIK);
        ui.locationMap.setMultiTouchControls(true);
        ui.locationMap.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        ui.locationMap.getController().setZoom(7.0);
        ui.locationMap.getController().setCenter(new GeoPoint(23.6850, 90.3563));
        CopyrightOverlay copyright = new CopyrightOverlay(this);
        copyright.setAlignRight(true);
        ui.locationMap.getOverlays().add(copyright);
        ui.locationCenter.setEnabled(false);
        ui.locationCenter.setOnClickListener(v -> centerLatest());
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        int ticket = ++connection;
        ui.locationParentStatus.setText(R.string.location_parent_loading);
        ui.locationParentDetail.setText("");
        FirebaseParent.get().verifyAccess(childUid,
            () -> { if (started && ticket == connection) attachLocation(); },
            () -> { if (started && ticket == connection) showFailure(); });
    }

    private void attachLocation() {
        if (!started || isFinishing() || isDestroyed()) return;
        if (registration != null) registration.remove();
        int ticket = connection;
        registration = FirebaseParent.get().listenLocation(childUid,
            result -> { if (ticket == connection) renderLocation(result); },
            () -> { if (ticket == connection) showFailure(); });
    }

    private void renderLocation(FirebaseParent.LocationResult result) {
        if (!started || isFinishing() || isDestroyed()) return;
        LocationSnapshot location = result.location;
        if (location == null) {
            latestPoint = null;
            ui.locationCenter.setEnabled(false);
            if (marker != null) { ui.locationMap.getOverlays().remove(marker); marker = null; }
            ui.locationParentStatus.setText(R.string.location_parent_empty);
            ui.locationParentDetail.setText(result.cached ? getString(R.string.location_parent_cached) : "");
            ui.locationMap.invalidate();
            return;
        }
        latestPoint = new GeoPoint(location.latitude, location.longitude);
        if (marker == null) {
            marker = new Marker(ui.locationMap);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(ParentDevices.name(this, childUid));
            ui.locationMap.getOverlays().add(marker);
        }
        marker.setPosition(latestPoint);
        marker.setSubDescription(getString(R.string.location_parent_detail,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(location.timeMs)),
            location.accuracyM, location.provider));
        boolean stale = location.isStale(System.currentTimeMillis());
        ui.locationParentStatus.setText(location.mock ? R.string.location_parent_mock
            : stale ? R.string.location_parent_stale : R.string.location_parent_live);
        String detail = marker.getSubDescription();
        if (result.cached) detail += "\n" + getString(R.string.location_parent_cached);
        ui.locationParentDetail.setText(detail);
        ui.locationCenter.setEnabled(true);
        if (!centered) centerLatest();
        ui.locationMap.invalidate();
    }

    private void centerLatest() {
        if (latestPoint == null) return;
        centered = true;
        ui.locationMap.getController().setZoom(16.0);
        ui.locationMap.getController().animateTo(latestPoint);
    }

    private void showFailure() {
        if (!started || isFinishing() || isDestroyed()) return;
        latestPoint = null;
        if (marker != null) { ui.locationMap.getOverlays().remove(marker); marker = null; }
        ui.locationMap.invalidate();
        ui.locationParentStatus.setText(R.string.location_parent_failed);
        ui.locationParentDetail.setText("");
        ui.locationCenter.setEnabled(false);
    }

    @Override protected void onResume() { super.onResume(); ui.locationMap.onResume(); }
    @Override protected void onPause() { ui.locationMap.onPause(); super.onPause(); }
    @Override protected void onStop() {
        started = false;
        connection++;
        if (registration != null) { registration.remove(); registration = null; }
        super.onStop();
    }
    @Override protected void onDestroy() { ui.locationMap.onDetach(); super.onDestroy(); }
}
