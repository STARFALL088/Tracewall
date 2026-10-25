package tracewall.child;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.List;
import tracewall.child.block.BlocklistStore;
import tracewall.child.databinding.ActivityMainBinding;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.child.log.QueryLogProvider;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.AppBlockActivity;
import tracewall.child.ui.QueryAdapter;
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
            else stopFiltering();
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
        ui.copyCsv.setOnClickListener(v -> copyCsv());
        ui.clearHistory.setOnClickListener(v -> confirmClearHistory());
        ui.deviceCode.setOnClickListener(v -> copyDeviceCode());

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
                BlocklistStore.get().allow(this, e.host);
                render();
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
            StringBuilder sb = new StringBuilder(
                "time_ms,host,type,blocked,uid,policy,latency_ms,resolver\n"
            );
            int rows = 0;
            try (
                Cursor c = getContentResolver().query(
                    QueryLogProvider.CONTENT_URI,
                    null,
                    null,
                    null,
                    null
                )
            ) {
                if (c != null) {
                    while (c.moveToNext()) {
                        sb.append(c.getLong(c.getColumnIndexOrThrow("time_ms")))
                            .append(',')
                            .append(
                                c.getString(c.getColumnIndexOrThrow("host"))
                            )
                            .append(',')
                            .append(c.getInt(c.getColumnIndexOrThrow("type")))
                            .append(',')
                            .append(
                                c.getInt(c.getColumnIndexOrThrow("blocked"))
                            )
                            .append(',')
                            .append(c.getInt(c.getColumnIndexOrThrow("uid")))
                            .append(',')
                            .append(
                                c.getString(c.getColumnIndexOrThrow("policy"))
                            )
                            .append(',')
                            .append(
                                c.getLong(c.getColumnIndexOrThrow("latency_ms"))
                            )
                            .append(',')
                            .append(
                                c.getString(c.getColumnIndexOrThrow("resolver"))
                            )
                            .append('\n');
                        rows++;
                    }
                }
            } catch (RuntimeException e) {
                runOnUiThread(() ->
                    Toast.makeText(
                        this,
                        R.string.csv_failed,
                        Toast.LENGTH_LONG
                    ).show()
                );
                return;
            }

            final int count = rows;
            final String csv = sb.toString();
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(
                    Context.CLIPBOARD_SERVICE
                );
                if (cm != null) cm.setPrimaryClip(
                    ClipData.newPlainText("Tracewall history", csv)
                );
                Toast.makeText(
                    this,
                    getString(R.string.csv_copied, count),
                    Toast.LENGTH_LONG
                ).show();
            });
        }, "tracewall-export").start();
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
