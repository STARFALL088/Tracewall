package tracewall.child.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    private Thread appScan;
    private boolean loading = true, loadFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Create views and restore search before the asynchronous app scan completes.
        android.util.Log.d(TAG, "AppBlockActivity.onCreate");
        ui = ActivityAppBlockBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        ui.appsToolbar.setNavigationOnClickListener(v -> finish());

        adapter = new AppBlockAdapter((app, block) -> {
            if (block) {
                BlocklistStore.get().blockApp(this, app.uid);
                adapter.setBlocked(app.uid, BlocklistStore.get().isAppBlocked(app.uid));
            }
            else {
                // Keep every package sharing this UID blocked until the PIN succeeds.
                adapter.setBlocked(app.uid, true);
                PinGate.require(this, () -> {
                    BlocklistStore.get().unblockApp(this, app.uid);
                    adapter.setBlocked(app.uid, BlocklistStore.get().isAppBlocked(app.uid));
                });
            }
        });
        ui.list.setLayoutManager(new LinearLayoutManager(this));
        ui.list.setAdapter(adapter);
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { renderState(); }
        });
        ui.appsStateAction.setOnClickListener(v -> {
            if (loadFailed) loadApps();
            else ui.filter.setText("");
        });

        ui.filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.setFilter(s.toString());
            }
        });
        ui.filter.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
            return true;
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
        // PackageManager itself may finish later; no callback can outlive this screen.
        android.util.Log.d(TAG, "AppBlockActivity.onDestroy");
        if (appScan != null) appScan.interrupt();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("apps_filter", ui.filter.getText().toString());
        state.putParcelable("apps_scroll", pendingScroll != null ? pendingScroll : ui.list.getLayoutManager().onSaveInstanceState());
        super.onSaveInstanceState(state);
    }

    private void loadApps() {
        loading = true;
        loadFailed = false;
        renderState();
        appScan = new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<AppBlockAdapter.App> apps = new ArrayList<>();
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    if (Thread.currentThread().isInterrupted()) return;
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
                if (Thread.currentThread().isInterrupted()) return;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    adapter.submit(apps);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    loadFailed = true;
                    renderState();
                });
            }
        }, "tracewall-apps");
        appScan.start();
    }

    /** Filtering and a truly empty inventory are different, recoverable states. */
    private void renderState() {
        ui.appsCount.setText(loading ? getString(R.string.detail_apps_loading)
                : loadFailed ? getString(R.string.detail_apps_error_title)
                : getString(R.string.detail_apps_count, adapter.appCount(), adapter.blockedCount()));
        ui.appsProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        ui.filter.setEnabled(!loading && !loadFailed && adapter.appCount() > 0);
        boolean hasRows = !loading && !loadFailed && adapter.getItemCount() > 0;
        ui.list.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        ui.appsState.setVisibility(hasRows ? View.GONE : View.VISIBLE);
        if (hasRows) {
            if (pendingScroll != null) {
                ui.list.getLayoutManager().onRestoreInstanceState(pendingScroll);
                pendingScroll = null;
            }
            return;
        }
        boolean noMatches = !loading && !loadFailed && adapter.appCount() > 0;
        ui.appsStateTitle.setText(loading ? R.string.detail_apps_loading_title
                : loadFailed ? R.string.detail_apps_error_title
                : noMatches ? R.string.detail_apps_no_results_title : R.string.detail_apps_empty_title);
        ui.empty.setText(loading ? R.string.detail_apps_loading_body
                : loadFailed ? R.string.detail_apps_error_body
                : noMatches ? R.string.detail_apps_no_results_body : R.string.detail_apps_empty_body);
        ui.appsStateAction.setVisibility(loadFailed || noMatches ? View.VISIBLE : View.GONE);
        ui.appsStateAction.setText(loadFailed ? R.string.detail_retry : R.string.detail_apps_clear_search);
    }
}
