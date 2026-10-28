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

    private ActivityAppBlockBinding ui;
    private AppBlockAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        ui.filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.setFilter(s.toString());
            }
        });

        loadApps();
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
                adapter.submit(apps);
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
