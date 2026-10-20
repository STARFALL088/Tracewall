package tracewall.child;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import tracewall.child.databinding.ActivityMainBinding;
import tracewall.child.block.BlocklistStore;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.child.ui.QueryAdapter;
import tracewall.child.ui.TrendView;
import tracewall.child.vpn.DnsVpnService;

/**
 * One screen: turn protection on, watch what it is doing.
 *
 * Polling rather than a bound service: the log writer is a worker pool inside
 * the service, and a 700 ms refresh is cheaper to reason about than an IPC
 * surface for a demo-sized app. The adapter is rebind-everything because the
 * visible window is small.
 */
public class MainActivity extends AppCompatActivity {

    private static final long TICK_MS = 700;
    private static final long BUCKET_MS = 60_000L;   // one bar = one minute

    private ActivityMainBinding ui;
    private QueryAdapter adapter;
    private boolean updatingToggle;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private final ActivityResultLauncher<Intent> consent = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startFiltering();
                } else {
                    updatingToggle = true;
                    ui.toggle.setChecked(false);
                    updatingToggle = false;
                    Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show();
                }
                render();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        adapter = new QueryAdapter(new AppLabels(getPackageManager()), this::askAllow);
        ui.list.setLayoutManager(new LinearLayoutManager(this));
        ui.list.setAdapter(adapter);

        ui.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingToggle) return;
            if (checked) requestStart(); else stopFiltering();
        });

        if (savedInstanceState == null) QueryLog.get().clear();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(refresh);
    }

    private void requestStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            consent.launch(prepare);          // first run: system consent dialog
            return;
        }
        startFiltering();
    }

    private void startFiltering() {
        startService(new Intent(this, DnsVpnService.class)
                .setAction(DnsVpnService.ACTION_START));
        // establish() runs on the service thread; give it a moment so the
        // toggle does not flick back before the tunnel is actually up.
        ticker.postDelayed(this::render, 600);
    }

    private void stopFiltering() {
        startService(new Intent(this, DnsVpnService.class)
                .setAction(DnsVpnService.ACTION_STOP));
        render();
    }

    /** Long-press flow: confirm, then let the policy engine reload in place. */
    private void askAllow(String host) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.allow_title)
                .setMessage(getString(R.string.allow_body, host))
                .setPositiveButton(R.string.allow_yes, (dialog, which) -> {
                    BlocklistStore.get().allow(this, host);
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void render() {
        boolean on = DnsVpnService.isRunning();
        updatingToggle = true;
        if (ui.toggle.isChecked() != on) ui.toggle.setChecked(on);
        updatingToggle = false;

        ui.status.setText(on ? R.string.status_on : R.string.status_off);

        QueryLog.Stats s = QueryLog.get().stats();
        ui.detail.setText(getString(R.string.detail, s.total, s.blocked,
                DnsVpnService.ruleCount()));

        List<QueryLog.Entry> recent = QueryLog.get().snapshot(60);
        adapter.submit(recent);
        updateTrend(recent);
    }

    /** Bucket the visible history into one bar per minute, oldest on the left. */
    private void updateTrend(List<QueryLog.Entry> entries) {
        int n = TrendView.buckets();
        int[] allowed = new int[n];
        int[] blocked = new int[n];
        long now = System.currentTimeMillis();

        for (QueryLog.Entry e : entries) {
            long age = now - e.timeMs;
            if (age < 0) age = 0;
            int idx = n - 1 - (int) (age / BUCKET_MS);
            if (idx < 0 || idx >= n) continue;
            if (e.blocked) blocked[idx]++; else allowed[idx]++;
        }
        ui.trend.setSeries(allowed, blocked);
    }
}
