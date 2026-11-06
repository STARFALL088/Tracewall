package tracewall.child.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;
import tracewall.blocklist.PolicyEngine;
import tracewall.blocklist.ScheduledProfile;
import tracewall.blocklist.WeeklySchedule;
import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
import tracewall.data.RulesJson;
import tracewall.security.PinStore;

/** Feature-level app-name blocking contract; no VPN, Firebase account or user PIN is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "en-rUS-w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class AppBlockActivityTest {
    private Application app;
    private int originalUid;
    private ActivityController<AppBlockActivity> controller;
    private AppBlockActivity activity;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        originalUid = app.getApplicationInfo().uid;
        app.getApplicationInfo().uid = 15_000;
        app.getSharedPreferences(BlocklistStore.PREFS, Application.MODE_PRIVATE).edit().clear().commit();
        BlocklistStore.get().load(app, Collections.emptySet());
        install("com.example.notes", "Notes", 12_001);
        install("com.example.roblox", "Roblox", 12_002);

        Map<String, String> memory = new HashMap<>();
        PinStore pin = new PinStore(new PinStore.Storage() {
            public String get(String key) { return memory.get(key); }
            public void put(String key, String value) { memory.put(key, value); }
        });
        pin.set("2468");
        ReflectionHelpers.setStaticField(PinGate.class, "instance", pin);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        ReflectionHelpers.setStaticField(PinGate.class, "instance", null);
        app.getSharedPreferences(BlocklistStore.PREFS, Application.MODE_PRIVATE).edit().clear().commit();
        app.getApplicationInfo().uid = originalUid;
        BlocklistStore.get().load(app, Collections.emptySet());
    }

    @Test public void nameSearchBlocksImmediatelyAndPinProtectsUnblocking() throws Exception {
        controller = Robolectric.buildActivity(AppBlockActivity.class).setup();
        activity = controller.get();
        Thread scan = ReflectionHelpers.getField(activity, "appScan");
        scan.join(5_000);
        assertFalse("installed-app scan timed out", scan.isAlive());
        idle();

        AppBlockAdapter adapter = ReflectionHelpers.getField(activity, "adapter");
        assertEquals(2, adapter.appCount());
        assertEquals(activity.getString(R.string.detail_apps_count, 2, 0), text(R.id.appsCount));

        ((EditText) activity.findViewById(R.id.filter)).setText("ROB");
        idle();
        assertEquals(1, adapter.getItemCount());
        AppBlockAdapter.Holder holder = adapter.onCreateViewHolder(
                (ViewGroup) activity.findViewById(R.id.list), 0);
        adapter.onBindViewHolder(holder, 0);
        assertEquals("Roblox", holder.b.appLabel.getText().toString());

        holder.b.appSwitch.setChecked(true);
        assertTrue(holder.b.appSwitch.isChecked());
        assertTrue(BlocklistStore.get().isAppBlocked(12_002));
        assertEquals(PolicyEngine.Verdict.BLOCK_APP,
                BlocklistStore.get().decide("study.example", 12_002).verdict);
        assertEquals(activity.getString(R.string.detail_apps_count, 2, 1), text(R.id.appsCount));

        adapter.onBindViewHolder(holder, 0);
        holder.b.appSwitch.setChecked(false);
        assertTrue(BlocklistStore.get().isAppBlocked(12_002));
        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.b.appSwitch.isChecked());
        Dialog gate = ShadowDialog.getLatestDialog();
        assertNotNull(gate);
        assertTrue(gate.isShowing());
        assertEquals(activity.getString(R.string.pin_required),
                ((TextView) gate.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        gate.findViewById(android.R.id.button2).performClick();
        idle();
        assertTrue(BlocklistStore.get().isAppBlocked(12_002));
    }

    @Test public void localAndRemoteRulesCannotBlockTracewallControlUid() {
        int controlUid = app.getApplicationInfo().uid;
        BlocklistStore.get().blockApp(app, controlUid);
        assertFalse(BlocklistStore.get().isAppBlocked(controlUid));

        BlocklistStore.get().applyRemoteRules(app, Collections.emptySet(),
                Collections.singleton(Integer.toString(controlUid)), null);
        assertFalse(BlocklistStore.get().isAppBlocked(controlUid));
        assertEquals(PolicyEngine.Verdict.ALLOW_DEFAULT,
                BlocklistStore.get().decide("sync.example", controlUid).verdict);

        Map<String, Boolean> toggles = new HashMap<>();
        for (String category : RulesJson.CATEGORIES) toggles.put(category, false);
        ScheduledProfile selfOnly = new ScheduledProfile("Unsafe import",
                new WeeklySchedule(true, WeeklySchedule.ALL_DAYS, 0, 1),
                Collections.emptySet(), Collections.singleton(controlUid));
        BlocklistStore.get().importLocal(app, new RulesJson(Collections.emptySet(),
                Collections.emptySet(), toggles, Collections.emptySet(), Collections.emptyMap(),
                Collections.singletonList(selfOnly)));

        PolicyEngine runtime = ReflectionHelpers.getField(BlocklistStore.get(), "engine");
        assertFalse(runtime.isAppBlocked(controlUid, 0L, TimeZone.getTimeZone("UTC")));
        assertEquals(PolicyEngine.Verdict.ALLOW_DEFAULT,
                runtime.decide("sync.example", controlUid, 0L, TimeZone.getTimeZone("UTC")).verdict);
    }

    private void install(String packageName, String label, int uid) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = uid;
        info.applicationInfo.nonLocalizedLabel = label;
        ShadowPackageManager packages = shadowOf(app.getPackageManager());
        packages.installPackage(info);
    }

    private String text(int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }
}
