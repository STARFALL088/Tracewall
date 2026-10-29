package tracewall.child.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
import tracewall.child.databinding.ActivityAppBlockBinding;

/**
 * Per-app blocking, one switch per installed app.
 *
 * System apps and anything under uid 10000 are not listed: those uids are the
 * platform itself, and the point of this screen is the TikToks and Robloxes of
 * the world. The list builds on a worker thread - PackageManager walks every
 * install and resolves every label, enough work to jank the first frames.
 */
public class AppBlockActivity extends AppCompatActivity {

    private static final String TAG = "TracewallLifecycle";
    private android.os.Parcelable pendingScroll;
    private ActivityAppBlockBinding ui;
    private AppBlockAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Create views and restore search before the asynchronous app scan completes.
        android.util.Log.d(TAG, "AppBlockActivity.onCreate");
        ui = ActivityAppBlockBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        adapter = new AppBlockAdapter((app, block) -> {
            if (block) BlocklistStore.get().blockApp(this, app.uid);
            else {
                app.blocked = true;
                adapter.notifyDataSetChanged();
                PinGate.require(this, () -> {
                    BlocklistStore.get().unblockApp(this, app.uid);
                    app.blocked = BlocklistStore.get().isAppBlocked(app.uid);
                    adapter.notifyDataSetChanged();
                    renderCount();
                });
            }
            renderCount();
        });
        ui.list.setLayoutManager(new LinearLayoutManager(this));
        ui.list.setAdapter(adapter);
        adapter.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);

        ui.filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.setFilter(s.toString());
            }
        });

        if (savedInstanceState != null) {
            pendingScroll = savedInstanceState.getParcelable("apps_scroll");
            ui.filter.setText(savedInstanceState.getString("apps_filter", ""));
        }
        loadApps();
    }

    @Override protected void onStart() {
        super.onStart();
        // Views become visible; no service action is issued.
        android.util.Log.d(TAG, "AppBlockActivity.onStart");
    }
    @Override protected void onResume() {
        super.onResume();
        // The user can now search and edit app rules.
        android.util.Log.d(TAG, "AppBlockActivity.onResume");
    }
    @Override protected void onPause() {
        // Lose focus without changing the VPN or rules.
        android.util.Log.d(TAG, "AppBlockActivity.onPause");
        super.onPause();
    }
    @Override protected void onStop() {
        // A stopped activity does not own the VPN lifetime.
        android.util.Log.d(TAG, "AppBlockActivity.onStop");
        super.onStop();
    }
    @Override protected void onRestart() {
        super.onRestart();
        // Resume the existing screen state from the back stack.
        android.util.Log.d(TAG, "AppBlockActivity.onRestart");
    }
    @Override protected void onDestroy() {
        // App-scan completion checks isDestroyed before touching these views.
        android.util.Log.d(TAG, "AppBlockActivity.onDestroy");
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("apps_filter", ui.filter.getText().toString());
        state.putParcelable("apps_scroll", pendingScroll != null ? pendingScroll : ui.list.getLayoutManager().onSaveInstanceState());
        super.onSaveInstanceState(state);
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<AppBlockAdapter.App> apps = new ArrayList<>();
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || ai.uid < 10000) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system) continue;
                apps.add(new AppBlockAdapter.App(
                        String.valueOf(ai.loadLabel(pm)),
                        pi.packageName,
                        ai.uid,
                        BlocklistStore.get().isAppBlocked(ai.uid)));
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                adapter.submit(apps);
                if (pendingScroll != null && adapter.getItemCount() > 0) {
                    ui.list.getLayoutManager().onRestoreInstanceState(pendingScroll);
                    pendingScroll = null;
                }
                renderCount();
                boolean none = apps.isEmpty();
                ui.empty.setVisibility(none ? View.VISIBLE : View.GONE);
                ui.list.setVisibility(none ? View.GONE : View.VISIBLE);
                ui.filter.setEnabled(!none);
            });
        }, "tracewall-apps").start();
    }

    /** The subtitle line, so the current block count is visible at a glance. */
    private void renderCount() {
        ui.appsCount.setText(getString(R.string.apps_count, adapter.blockedCount()));
    }
}
