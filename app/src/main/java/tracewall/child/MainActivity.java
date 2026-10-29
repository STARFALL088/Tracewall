package tracewall.child;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.util.List;
import tracewall.child.block.BlocklistStore;
import tracewall.child.databinding.ActivityMainBinding;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.child.log.QueryLogProvider;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.AppBlockActivity;
import tracewall.child.ui.QueryAdapter;
import tracewall.child.ui.PinGate;
import tracewall.child.ui.TrendView;
import tracewall.child.vpn.DnsVpnService;

/**
 * One screen: turn protection on, watch what it is doing, export or clear what
 * it has seen.
 *
 * Polling rather than a bound service: the log writer is a worker pool inside
 * the service, and a 700 ms refresh is cheaper to reason about than an IPC
 * surface at this size. Events (start, stop, export, clear) still go through
 * real Intents and the ContentProvider.
 */
public class MainActivity extends AppCompatActivity {

    private static final long TICK_MS = 700;
    private static final long BUCKET_MS = 60_000L; // one trend bar = one minute

    private ActivityMainBinding ui;
    private QueryAdapter adapter;
    private AppLabels labels;
    private boolean updatingToggle;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private final ActivityResultLauncher<String> notificationPermission =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {}
        );

    private final ActivityResultLauncher<Intent> consent =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startFiltering();
                } else {
                    updatingToggle = true;
                    ui.toggle.setChecked(false);
                    updatingToggle = false;
                    Toast.makeText(
                        this,
                        R.string.vpn_denied,
                        Toast.LENGTH_LONG
                    ).show();
                }
                render();
            }
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        labels = new AppLabels(getPackageManager());
        adapter = new QueryAdapter(labels, this::askRow);
        ui.list.setLayoutManager(new LinearLayoutManager(this));
        ui.list.setAdapter(adapter);

        ui.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingToggle) return;
            if (checked) requestStart();
            else {
                render();
                PinGate.require(this, this::stopFiltering);
            }
        });

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                "android.permission.POST_NOTIFICATIONS"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(
                "android.permission.POST_NOTIFICATIONS"
            );
        }

        ui.actionApps.setOnClickListener(v ->
            startActivity(new Intent(this, AppBlockActivity.class))
        );
        ui.makePermanent.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(R.string.make_permanent)
            .setMessage(R.string.permanent_body)
            .setPositiveButton(android.R.string.ok, (d, w) ->
                openVpnSettings())
            .setNegativeButton(android.R.string.cancel, null).show());
        ui.manageAllows.setOnClickListener(v -> showAllows());
        PinGate.ensureSet(this);
        ui.copyCsv.setOnClickListener(v -> PinGate.require(this, this::copyCsv));
        ui.clearHistory.setOnClickListener(v -> confirmClearHistory());
        ui.deviceCode.setOnClickListener(v -> PinGate.require(this, this::showQrDialog));
        ui.manageParents.setOnClickListener(v -> PinGate.require(this, () ->
            FirebaseSync.get().listParents(parents -> new AlertDialog.Builder(this)
                .setTitle(R.string.revoke_parent)
                .setItems(parents.toArray(new String[0]), (d, which) ->
                    FirebaseSync.get().revokeParent(parents.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show())));
        ui.deviceCode.setOnLongClickListener(v -> {
            copyDeviceCode();
            return true;
        });

        if (savedInstanceState == null) QueryLog.get().clear();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.post(refresh);
        checkPrivateDns();
        updateDeviceCode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(refresh);
    }

    // ------------------------------------------------------- warnings / info

    /**
     * Warn when Private DNS (DoH/DoT) is enabled. Android resolves names via
     * an encrypted channel to a named server on port 853, which our TUN route
     * (10.66.0.1/32, UDP only) never sees. The warning appears on every
     * resume so it tracks the system setting in real time.
     */
    private void checkPrivateDns() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        String mode = Settings.Global.getString(
            getContentResolver(),
            "private_dns_mode"
        );
        boolean active = mode != null && !mode.equals("off");
        ui.privateDnsWarning.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    /** Show the Firebase UID as a tappable device code for pairing. */
    private void updateDeviceCode() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) {
            ui.deviceCode.setVisibility(View.GONE);
            return;
        }
        ui.deviceCode.setText(getString(R.string.device_code_label, uid));
        ui.deviceCode.setVisibility(View.VISIBLE);
    }

    /** Tap: show the UID as a QR code the parent can scan directly. */
    private void showQrDialog() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) return;
        FirebaseSync.get().createPairing(pairing -> showPairingQr(pairing), () ->
            Toast.makeText(this, R.string.pairing_create_failed, Toast.LENGTH_LONG).show());
    }

    private void showPairingQr(tracewall.security.PairingCode pairing) {
        if (isFinishing() || isDestroyed()) return;
        try {
            int size = 512;
            BitMatrix matrix = new MultiFormatWriter().encode(
                pairing.payload(),
                BarcodeFormat.QR_CODE,
                size,
                size
            );
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (
                int x = 0;
                x < size;
                x++
            ) pixels[y * size + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap bmp = Bitmap.createBitmap(
                pixels,
                size,
                size,
                Bitmap.Config.ARGB_8888
            );
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setPadding(32, 32, 32, 32);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.qr_title)
                .setMessage(getString(R.string.pairing_details, pairing.childUid, pairing.code))
                .setView(iv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Exception e) {
            android.util.Log.e("Tracewall", "QR generation failed", e);
            copyDeviceCode(); // fall back to clipboard
        }
    }

    /** Long-press: copy the full UID to the clipboard as plain text. */
    private void copyDeviceCode() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(
            Context.CLIPBOARD_SERVICE
        );
        if (cm != null) cm.setPrimaryClip(
            ClipData.newPlainText("device_code", uid)
        );
        Toast.makeText(
            this,
            R.string.device_code_copied,
            Toast.LENGTH_SHORT
        ).show();
    }

    // ------------------------------------------------------------ protection

    private void showAllows() {
        String[] hosts = BlocklistStore.get().localAllows(this).toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(R.string.manage_allows)
            .setItems(hosts, (d, which) -> PinGate.require(this, () -> {
                BlocklistStore.get().removeAllow(this, hosts[which]);
                render();
            }))
            .setMessage(hosts.length == 0 ? getString(R.string.no_allows) : null)
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void requestStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            consent.launch(prepare); // first run: system consent dialog
            return;
        }
        startFiltering();
    }

    private void startFiltering() {
        startService(
            new Intent(this, DnsVpnService.class).setAction(
                DnsVpnService.ACTION_START
            )
        );
        // establish() runs on the service thread; give it a moment so the
        // toggle does not flick back before the tunnel is actually up.
        ticker.postDelayed(this::render, 600);
    }

    private void stopFiltering() {
        startService(
            new Intent(this, DnsVpnService.class).setAction(
                DnsVpnService.ACTION_STOP
            )
        );
        render();
    }

    /**
     * Long-press flow, on any row: allow that host, or block the app that
     * queried it. A common parent flow is scrolling history, seeing TikTok
     * lookups, and deciding the whole app has to die - no rules list needed.
     *
     * Blocking is offered only for uid >= 10000: system uids belong to the
     * platform itself and blocking one would break parts of Android for no
     * parental gain.
     */
    private void askRow(QueryLog.Entry e) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(e.host)
            .setMessage(getString(R.string.row_body, labels.label(e.uid)))
            .setPositiveButton(R.string.allow_yes, (dialog, which) -> {
                PinGate.require(this, () -> {
                    BlocklistStore.get().allow(this, e.host);
                    render();
                });
            });
        if (e.uid >= 10000) {
            b.setNegativeButton(R.string.block_app_yes, (dialog, which) -> {
                BlocklistStore.get().blockApp(this, e.uid);
                Toast.makeText(
                    this,
                    getString(R.string.app_blocked_toast, labels.label(e.uid)),
                    Toast.LENGTH_SHORT
                ).show();
                render();
            }).setNeutralButton(android.R.string.cancel, null);
        } else {
            b.setNegativeButton(android.R.string.cancel, null);
        }
        b.show();
    }

    // --------------------------------------------------------------- history

    /**
     * Read history through the ContentProvider rather than the in-memory ring,
     * so the export path exercises the same component a parent app would use.
     */
    private void copyCsv() {
        new Thread(() -> {
            try {
                java.io.File file = tracewall.child.io.ShareFiles.target(this, "history.csv");
                try (java.io.Writer writer = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8);
                     Cursor c = getContentResolver().query(QueryLogProvider.CONTENT_URI,
                         null, null, null, null)) {
                    writer.write("time_ms,host,type,blocked,uid,policy,latency_ms,resolver\r\n");
                    if (c == null) throw new java.io.IOException("History unavailable");
                    String[] columns = {"time_ms", "host", "type", "blocked", "uid", "policy", "latency_ms", "resolver"};
                    while (c.moveToNext()) {
                        Object[] row = new Object[columns.length];
                        for (int i = 0; i < columns.length; i++) row[i] = c.getString(c.getColumnIndexOrThrow(columns[i]));
                        writer.write(tracewall.data.CsvRow.of(row));
                    }
                }
                runOnUiThread(() -> tracewall.child.io.ShareFiles.send(this, file, "text/csv"));
            } catch (java.io.IOException | RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.csv_failed, Toast.LENGTH_LONG).show());
            }
        }, "tracewall-export").start();
    }

    private void openVpnSettings() {
        for (String action : new String[] {Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS}) {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) == null) continue;
            try { startActivity(intent); return; }
            catch (android.content.ActivityNotFoundException ignored) { /* Try general settings. */ }
        }
        Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_body)
            .setPositiveButton(R.string.clear_yes, (dialog, which) ->
                clearHistory()
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void clearHistory() {
        new Thread(() -> {
            int removed = 0;
            try {
                removed = getContentResolver().delete(
                    Uri.parse(QueryLogProvider.CONTENT_URI + "?limit=5000"),
                    null,
                    null
                );
            } catch (RuntimeException ignored) {
                // the toast reports what actually happened, including a failure
            }
            final int count = removed;
            runOnUiThread(() -> {
                QueryLog.get().clear();
                Toast.makeText(
                    this,
                    getString(R.string.clear_done, count),
                    Toast.LENGTH_SHORT
                ).show();
            });
        }, "tracewall-clear").start();
    }

    // ---------------------------------------------------------------- render

    private void render() {
        boolean on = DnsVpnService.isRunning();
        updatingToggle = true;
        if (ui.toggle.isChecked() != on) ui.toggle.setChecked(on);
        updatingToggle = false;

        ui.status.setText(on ? R.string.status_on : R.string.status_off);

        QueryLog.Stats s = QueryLog.get().stats();
        // Rule count comes from the live policy, not the service's boot-time
        // snapshot: allowing a host changes it without restarting the tunnel.
        ui.detail.setText(
            getString(
                R.string.detail,
                s.total,
                s.blocked,
                BlocklistStore.get().size()
            )
        );

        List<QueryLog.Entry> recent = QueryLog.get().snapshot(60);
        adapter.submit(recent);
        updateTrend(recent);
    }

    /** Bucket the visible history into one bar per minute, oldest on the left. */
    private void updateTrend(List<QueryLog.Entry> recent) {
        int n = TrendView.buckets();
        int[] allowed = new int[n];
        int[] blocked = new int[n];
        long now = System.currentTimeMillis();

        for (QueryLog.Entry e : recent) {
            long age = now - e.timeMs;
            if (age < 0) age = 0;
            int idx = n - 1 - (int) (age / BUCKET_MS);
            if (idx < 0 || idx >= n) continue;
            if (e.blocked) blocked[idx]++;
            else allowed[idx]++;
        }
        ui.trend.setSeries(allowed, blocked);
    }
}
