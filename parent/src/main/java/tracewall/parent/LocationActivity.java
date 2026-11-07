package tracewall.parent;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import tracewall.data.LocationSnapshot;
import tracewall.parent.databinding.ActivityLocationBinding;
import tracewall.parent.sync.FirebaseParent;

/** Verified parent map showing every saved child's latest shared point at once. */
public final class LocationActivity extends AppCompatActivity {
    private static final int[] MARKER_COLORS = {
        0xff2f6fed, 0xffd54b4b, 0xff138a72, 0xff8a55c5, 0xffc16b12,
        0xff347a9a, 0xffb03a78, 0xff607d3b
    };
    private ActivityLocationBinding ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ListenerRegistration> registrations = new HashMap<>();
    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, FirebaseParent.LocationResult> results = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private List<Map.Entry<String, String>> devices = new ArrayList<>();
    private boolean started;
    private boolean fitted;
    private int connection;

    private final Runnable freshness = new Runnable() {
        @Override public void run() {
            renderSummary();
            for (Map.Entry<String, FirebaseParent.LocationResult> row : results.entrySet())
                if (row.getValue().location != null) renderMarker(row.getKey(), row.getValue());
            main.postDelayed(this, 30_000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        ui = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        ui.locationToolbar.setNavigationOnClickListener(v -> finish());

        ui.locationMap.setTileSource(TileSourceFactory.MAPNIK);
        ui.locationMap.setMultiTouchControls(true);
        ui.locationMap.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        ui.locationMap.getController().setZoom(7.0);
        ui.locationMap.getController().setCenter(new GeoPoint(23.6850, 90.3563));
        CopyrightOverlay copyright = new CopyrightOverlay(this);
        copyright.setAlignRight(true);
        ui.locationMap.getOverlays().add(copyright);
        ui.locationCenter.setEnabled(false);
        ui.locationCenter.setOnClickListener(v -> fitAll());
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        int ticket = ++connection;
        clearSession();
        devices = new ArrayList<>(ParentDevices.all(this).names().entrySet());
        devices.sort(Comparator.comparing(Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER));
        fitted = false;
        renderSummary();
        for (Map.Entry<String, String> device : devices) {
            String uid = device.getKey();
            FirebaseParent.get().verifyAccess(uid,
                () -> { if (active(ticket)) attachLocation(uid, ticket); },
                () -> { if (active(ticket)) { failed.add(uid); loaded.add(uid); renderSummary(); maybeFit(); } });
        }
        main.post(freshness);
    }

    private boolean active(int ticket) {
        return started && ticket == connection && !isFinishing() && !isDestroyed();
    }

    private void attachLocation(String uid, int ticket) {
        ListenerRegistration registration = FirebaseParent.get().listenLocation(uid,
            result -> {
                if (!active(ticket)) return;
                loaded.add(uid);
                results.put(uid, result);
                if (result.location == null) removeMarker(uid);
                else renderMarker(uid, result);
                renderSummary();
                maybeFit();
            }, () -> {
                if (!active(ticket)) return;
                failed.add(uid);
                loaded.add(uid);
                results.remove(uid);
                removeMarker(uid);
                renderSummary();
                maybeFit();
            });
        registrations.put(uid, registration);
    }

    private void renderMarker(String uid, FirebaseParent.LocationResult result) {
        LocationSnapshot location = result.location;
        if (location == null) return;
        Marker marker = markers.get(uid);
        if (marker == null) {
            marker = new Marker(ui.locationMap);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(name(uid));
            Drawable icon = AppCompatResources.getDrawable(this, tracewall.auth.R.drawable.ic_tw_location);
            if (icon != null) {
                icon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(icon, MARKER_COLORS[Math.floorMod(uid.hashCode(), MARKER_COLORS.length)]);
                marker.setIcon(icon);
            }
            markers.put(uid, marker);
            ui.locationMap.getOverlays().add(marker);
        }
        marker.setPosition(new GeoPoint(location.latitude, location.longitude));
        marker.setSubDescription(detail(location, result.cached));
        ui.locationMap.invalidate();
    }

    private void renderSummary() {
        if (devices.isEmpty()) {
            ui.locationParentStatus.setText(R.string.location_family_none_saved);
            ui.locationParentDetail.setText("");
            ui.locationCenter.setEnabled(false);
            return;
        }
        int points = currentPoints().size();
        if (loaded.size() < devices.size()) ui.locationParentStatus.setText(R.string.location_parent_loading);
        else if (points == 0) ui.locationParentStatus.setText(R.string.location_family_none);
        else ui.locationParentStatus.setText(getString(R.string.location_family_count, points, devices.size()));

        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, String> device : devices) {
            String uid = device.getKey(), state;
            FirebaseParent.LocationResult result = results.get(uid);
            if (failed.contains(uid)) state = getString(R.string.location_family_unavailable);
            else if (!loaded.contains(uid)) state = getString(R.string.location_family_checking);
            else if (result == null || result.location == null) state = getString(R.string.location_family_not_sharing);
            else state = summary(result.location, result.cached);
            rows.add(getString(R.string.location_family_row, device.getValue(), state));
        }
        ui.locationParentDetail.setText(android.text.TextUtils.join("\n", rows));
        ui.locationCenter.setEnabled(points > 0);
    }

    private String summary(LocationSnapshot location, boolean cached) {
        String state = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(location.timeMs))
            + " · " + getString(R.string.location_family_accuracy, location.accuracyM);
        if (location.mock) state += " · " + getString(R.string.location_family_mock_tag);
        else if (location.isStale(System.currentTimeMillis()))
            state += " · " + getString(R.string.location_family_stale_tag);
        if (cached) state += " · " + getString(R.string.location_family_cached_tag);
        return state;
    }

    private String detail(LocationSnapshot location, boolean cached) {
        String value = getString(R.string.location_parent_detail,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(location.timeMs)),
            location.accuracyM, location.provider);
        if (location.mock) value += "\n" + getString(R.string.location_parent_mock);
        else if (location.isStale(System.currentTimeMillis()))
            value += "\n" + getString(R.string.location_parent_stale);
        if (cached) value += "\n" + getString(R.string.location_parent_cached);
        return value;
    }

    private String name(String uid) {
        for (Map.Entry<String, String> row : devices) if (row.getKey().equals(uid)) return row.getValue();
        return uid;
    }

    private List<GeoPoint> currentPoints() {
        List<GeoPoint> points = new ArrayList<>();
        for (FirebaseParent.LocationResult result : results.values()) if (result.location != null)
            points.add(new GeoPoint(result.location.latitude, result.location.longitude));
        return points;
    }

    private void maybeFit() {
        if (!fitted && loaded.size() == devices.size() && !currentPoints().isEmpty()) fitAll();
    }

    private void fitAll() {
        List<GeoPoint> points = currentPoints();
        if (points.isEmpty()) return;
        fitted = true;
        if (points.size() == 1) {
            ui.locationMap.getController().setZoom(16.0);
            ui.locationMap.getController().animateTo(points.get(0));
            return;
        }
        BoundingBox box = BoundingBox.fromGeoPoints(points);
        ui.locationMap.post(() -> ui.locationMap.zoomToBoundingBox(box, true, 72));
    }

    private void removeMarker(String uid) {
        Marker marker = markers.remove(uid);
        if (marker != null) ui.locationMap.getOverlays().remove(marker);
        ui.locationMap.invalidate();
    }

    private void clearSession() {
        for (ListenerRegistration registration : registrations.values()) registration.remove();
        registrations.clear();
        for (Marker marker : markers.values()) ui.locationMap.getOverlays().remove(marker);
        markers.clear();
        results.clear();
        loaded.clear();
        failed.clear();
        ui.locationMap.invalidate();
    }

    @Override protected void onResume() { super.onResume(); ui.locationMap.onResume(); }
    @Override protected void onPause() { ui.locationMap.onPause(); super.onPause(); }
    @Override protected void onStop() {
        started = false;
        connection++;
        main.removeCallbacks(freshness);
        clearSession();
        super.onStop();
    }
    @Override protected void onDestroy() { ui.locationMap.onDetach(); super.onDestroy(); }
}
