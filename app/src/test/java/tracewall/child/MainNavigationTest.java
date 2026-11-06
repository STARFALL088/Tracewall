package tracewall.child;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.HistoryActivity;
import tracewall.child.ui.PinGate;
import tracewall.child.ui.RulesActivity;
import tracewall.security.PinStore;

/** Exercises real navigation with an inert SDK; no identity, credentials, or cloud data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "en-rUS-w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class MainNavigationTest {
    private MainActivity activity;

    @Test public void focusedPagesRestoreTheirStateWithoutStartingProtectionOrBypassingPin() {
        Application app = RuntimeEnvironment.getApplication();
        for (FirebaseApp existing : FirebaseApp.getApps(app)) existing.delete();
        FirebaseApp firebase = FirebaseApp.initializeApp(app, new FirebaseOptions.Builder()
            .setProjectId("demo-tracewall-navigation")
            .setApplicationId("1:123456789012:android:0123456789abcdef0123456789abcdef")
            .setApiKey("AIzaSy" + "0".repeat(33))
            .setDatabaseUrl("https://demo-tracewall-navigation-default-rtdb.firebaseio.com")
            .build());
        FirebaseAuth.getInstance().useEmulator("127.0.0.1", 9099);
        FirebaseDatabase.getInstance().useEmulator("127.0.0.1", 9000);
        FirebaseSync.get().initialize(app);
        FirebaseFirestore.getInstance().useEmulator("127.0.0.1", 8080);
        FirebaseFirestore.getInstance().disableNetwork();
        app.getSharedPreferences("tracewall.account.ui", Application.MODE_PRIVATE).edit()
            .putBoolean("account_choice_made", true).commit();
        Map<String, String> memory = new HashMap<>();
        PinStore pin = new PinStore(new PinStore.Storage() {
            public String get(String key) { return memory.get(key); }
            public void put(String key, String value) { memory.put(key, value); }
        });
        pin.set("2468"); // Synthetic test-only PIN; no user state is read.
        ReflectionHelpers.setStaticField(PinGate.class, "instance", pin);
        shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        idle();
        try {
            assertEquals(5, navigation().getMenu().size());
            assertPage(R.id.nav_home, R.id.homePage);
            assertEquals(activity.getString(R.string.status_off), text(R.id.status));
            assertFalse(activity.findViewById(R.id.deviceCode).isEnabled());

            select(R.id.nav_activity);
            assertPage(R.id.nav_activity, R.id.activityPage);
            assertEquals(activity.getString(R.string.nav_empty_title), text(R.id.emptyTitle));
            ((EditText) activity.findViewById(R.id.queryFilter)).setText("no-such-request.invalid");
            assertEquals(activity.getString(R.string.nav_empty_search_title), text(R.id.emptyTitle));
            select(R.id.nav_family);
            assertPage(R.id.nav_family, R.id.familyPage);
            select(R.id.nav_activity);
            assertEquals("no-such-request.invalid", text(R.id.queryFilter));

            select(R.id.nav_protect);
            ScrollView protectPage = activity.findViewById(R.id.protectPage);
            protectPage.scrollTo(0, 180);
            int protectScroll = protectPage.getScrollY();
            assertTrue(protectScroll > 0);
            select(R.id.nav_settings);
            ScrollView settingsPage = activity.findViewById(R.id.settingsPage);
            settingsPage.scrollTo(0, 120);
            int settingsScroll = settingsPage.getScrollY();
            assertTrue(settingsScroll > 0);
            // Recreate while both scroll owners are hidden; restoration waits until selection.
            select(R.id.nav_activity);

            controller.recreate();
            activity = controller.get();
            idle();
            assertPage(R.id.nav_activity, R.id.activityPage);
            assertEquals("no-such-request.invalid", text(R.id.queryFilter));
            select(R.id.nav_protect);
            assertEquals(protectScroll, ((ScrollView) activity.findViewById(R.id.protectPage)).getScrollY());
            select(R.id.nav_settings);
            assertEquals(settingsScroll, ((ScrollView) activity.findViewById(R.id.settingsPage)).getScrollY());
            select(R.id.nav_activity);
            activity.findViewById(R.id.emptyAction).performClick();
            assertEquals("", text(R.id.queryFilter));

            activity.findViewById(R.id.openHistory).performClick();
            Intent history = shadowOf(activity).getNextStartedActivity();
            assertNotNull(history);
            assertEquals(HistoryActivity.class.getName(), history.getComponent().getClassName());

            select(R.id.nav_protect);
            assertPage(R.id.nav_protect, R.id.protectPage);
            assertRuleShortcut(R.id.openRules, RulesActivity.PAGE_DOMAINS);
            assertRuleShortcut(R.id.openSchedules, RulesActivity.PAGE_SCHEDULES);
            select(R.id.nav_settings);
            assertPage(R.id.nav_settings, R.id.settingsPage);
            assertRuleShortcut(R.id.openBackup, RulesActivity.PAGE_BACKUP);

            activity.findViewById(R.id.clearHistory).performClick();
            idle();
            assertPinGate();
            activity.findViewById(R.id.accountButton).performClick();
            idle();
            assertPinGate();

            activity.getOnBackPressedDispatcher().onBackPressed();
            assertPage(R.id.nav_home, R.id.homePage);
            assertNull(shadowOf(app).getNextStartedService());
            assertNull(FirebaseAuth.getInstance().getCurrentUser());
        } finally {
            controller.pause().stop().destroy();
            ReflectionHelpers.setStaticField(PinGate.class, "instance", null);
            firebase.delete();
        }
    }

    private BottomNavigationView navigation() { return activity.findViewById(R.id.navigation); }
    private void select(int id) { navigation().setSelectedItemId(id); idle(); }
    private String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private void assertPinGate() {
        Dialog gate = ShadowDialog.getLatestDialog();
        assertNotNull(gate);
        assertTrue(gate.isShowing());
        TextView title = gate.findViewById(androidx.appcompat.R.id.alertTitle);
        assertNotNull(title);
        assertEquals(activity.getString(R.string.pin_required), title.getText().toString());
        gate.dismiss();
    }
    private void assertPage(int nav, int root) {
        assertEquals(nav, navigation().getSelectedItemId());
        for (int page : new int[] {R.id.homePage, R.id.activityPage, R.id.protectPage, R.id.familyPage, R.id.settingsPage})
            assertEquals(page == root ? View.VISIBLE : View.GONE, activity.findViewById(page).getVisibility());
    }
    private void assertRuleShortcut(int view, int page) {
        activity.findViewById(view).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(RulesActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(page, intent.getIntExtra(RulesActivity.EXTRA_PAGE, -1));
    }
}
