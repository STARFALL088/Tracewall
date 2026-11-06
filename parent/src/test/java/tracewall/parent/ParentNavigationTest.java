package tracewall.parent;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
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
import org.robolectric.util.ReflectionHelpers;
import tracewall.parent.databinding.ActivityAppPickerBinding;
import tracewall.parent.databinding.ActivityPairingBinding;
import tracewall.parent.databinding.ItemAppPickerHeaderBinding;

/** UI-only regression checks. Activities are created but NEVER started: no cloud listener,
 * access check, account request, fabricated Firebase snapshot, or production config is needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "en-rUS-w320dp-h480dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class ParentNavigationTest {
    private ActivityController<DashboardActivity> controller;
    private DashboardActivity activity;

    @Before public void localDeviceOnly() {
        Application app = RuntimeEnvironment.getApplication();
        assertTrue(ParentDevices.prefs(app).edit().clear().commit());
        ParentDevices.remember(app, "test-child-uid", "Study phone");
    }

    @After public void closeCreatedUi() {
        if (controller != null) controller.destroy();
    }

    private void create(Bundle state) {
        controller = Robolectric.buildActivity(DashboardActivity.class).create(state);
        activity = controller.get();
    }

    @Test public void fourDestinationsAreExclusiveAndBackHandlerReturnsToOverview() {
        create(null);
        assertEquals(4, navigation().getMenu().size());
        assertPage(R.id.nav_overview, R.id.overviewPage);
        int[] choices = {R.id.nav_activity, R.id.nav_rules, R.id.nav_devices};
        int[] roots = {R.id.activityPage, R.id.rulesPage, R.id.devicesPage};
        for (int i = 0; i < choices.length; i++) {
            navigation().setSelectedItemId(choices[i]);
            assertPage(choices[i], roots[i]);
            // Lifecycle-scoped callbacks are deliberately not registered with the dispatcher
            // until STARTED. Exercise the real handler without starting Firebase onStart().
            OnBackPressedCallback back = ReflectionHelpers.getField(activity, "pageBack");
            assertTrue(back.isEnabled());
            back.handleOnBackPressed();
            assertPage(R.id.nav_overview, R.id.overviewPage);
            assertFalse(back.isEnabled());
        }
        assertFalse(activity.isFinishing());
        assertNull(ReflectionHelpers.getField(activity, "historyReg"));
        assertNull(ReflectionHelpers.getField(activity, "eventsReg"));
        assertNull(ReflectionHelpers.getField(activity, "rulesReg"));
    }

    @Test public void destinationAndAdvancedDisclosureSurviveSavedState() {
        create(null);
        navigation().setSelectedItemId(R.id.nav_rules);
        activity.findViewById(R.id.advancedToggle).performClick();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.advancedRules).getVisibility());
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        assertEquals(R.id.nav_rules, saved.getInt("parent_page"));
        assertTrue(saved.getBoolean("parent_advanced"));
        assertEquals(4, saved.getIntArray("parent_scroll").length);
        controller.destroy(); controller = null;
        create(saved);
        assertPage(R.id.nav_rules, R.id.rulesPage);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.advancedRules).getVisibility());
        assertFalse(activity.findViewById(R.id.allowButton).isEnabled());
        assertFalse(activity.findViewById(R.id.blockUidButton).isEnabled());
        assertEquals(View.GONE, activity.findViewById(R.id.categoryControls).getVisibility());
    }

    @Test public void appShortcutsAndDeviceControlsAreObviousWithoutEnablingUnverifiedRules() {
        create(null);
        for (int shortcut : new int[]{R.id.overviewApps, R.id.rulesApps}) {
            navigation().setSelectedItemId(shortcut == R.id.overviewApps ? R.id.nav_overview : R.id.nav_rules);
            activity.findViewById(shortcut).performClick();
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals(AppPickerActivity.class.getName(), intent.getComponent().getClassName());
        }
        navigation().setSelectedItemId(R.id.nav_devices);
        String selected = ((TextView) activity.findViewById(R.id.childUidLabel)).getText().toString();
        assertTrue(selected.contains("Study phone"));
        assertTrue(selected.contains("test-child-uid"));
        for (int control : new int[]{R.id.switchDeviceButton, R.id.addDeviceButton, R.id.renameDeviceButton,
                R.id.forgetDeviceButton, R.id.forgetSavedButton, R.id.dashboardAccountButton})
            assertEquals(View.VISIBLE, activity.findViewById(control).getVisibility());
        assertFalse(activity.findViewById(R.id.allowButton).isEnabled());
        assertFalse(activity.findViewById(R.id.editRules).isEnabled());
        assertFalse(activity.findViewById(R.id.catAdult).isSaveEnabled());
    }

    @Test public void pairingCannotLeaveWhileDistributedRegistrationIsBusy() {
        Application app = RuntimeEnvironment.getApplication();
        for (FirebaseApp existing : FirebaseApp.getApps(app)) existing.delete();
        FirebaseApp firebase = FirebaseApp.initializeApp(app, new FirebaseOptions.Builder()
            .setProjectId("demo-tracewall-parent-navigation")
            .setApplicationId("1:123456789012:android:1123456789abcdef0123456789abcdef")
            .setApiKey("AIzaSy" + "0".repeat(33))
            .setDatabaseUrl("https://demo-tracewall-parent-navigation-default-rtdb.firebaseio.com")
            .build());
        ActivityController<PairingActivity> pairing = Robolectric.buildActivity(PairingActivity.class).create();
        try {
            PairingActivity screen = pairing.get();
            ReflectionHelpers.callInstanceMethod(screen, "setBusy",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
            assertNull(((com.google.android.material.appbar.MaterialToolbar)
                screen.findViewById(R.id.pairingToolbar)).getNavigationIcon());
            ReflectionHelpers.callInstanceMethod(screen, "leavePairing");
            assertFalse(screen.isFinishing());
            ReflectionHelpers.callInstanceMethod(screen, "setBusy",
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
            assertNotNull(((com.google.android.material.appbar.MaterialToolbar)
                screen.findViewById(R.id.pairingToolbar)).getNavigationIcon());
            ReflectionHelpers.callInstanceMethod(screen, "leavePairing");
            assertTrue(screen.isFinishing());
        } finally {
            pairing.destroy();
            firebase.delete();
        }
    }

    @Test public void narrowEnglishPagesWrapAtOnePointThreeFontScale() { assertNarrowPages(); }

    @Test @Config(qualifiers = "bn-rBD-w320dp-h480dp")
    public void narrowBengaliPagesWrapAtOnePointThreeFontScale() { assertNarrowPages(); }

    private void assertNarrowPages() {
        RuntimeEnvironment.setFontScale(1.3f);
        create(null);
        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        for (int choice : new int[]{R.id.nav_overview, R.id.nav_activity, R.id.nav_rules, R.id.nav_devices}) {
            navigation().setSelectedItemId(choice);
            if (choice == R.id.nav_rules) activity.findViewById(R.id.advancedToggle).performClick();
            measure(root, 320, 480);
            assertTextFits(root);
        }
        LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_TracewallParent));
        ActivityPairingBinding pairing = ActivityPairingBinding.inflate(inflater);
        measure(pairing.getRoot(), 320, 480);
        assertTextFits(pairing.getRoot());
        assertTrue(pairing.pairingScroll.getChildAt(0).getHeight() > pairing.pairingScroll.getHeight());
        pairing.pairingScroll.scrollTo(0, pairing.pairingScroll.getChildAt(0).getHeight());
        assertTrue(pairing.pairingScroll.getScrollY() > 0);
        assertFalse(pairing.pairingCodeInput.isSaveEnabled());
        assertNotNull(pairing.pairingToolbar.getNavigationIcon());
        ActivityAppPickerBinding picker = ActivityAppPickerBinding.inflate(inflater);
        measure(picker.getRoot(), 320, 480);
        assertNotNull(picker.appsToolbar.getNavigationIcon());
        assertTrue(picker.appsList.getHeight() > 0);
        ItemAppPickerHeaderBinding header = ItemAppPickerHeaderBinding.inflate(inflater);
        measure(header.getRoot(), 280, 1600);
        assertTextFits(header.getRoot());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, header.appsSearch.getLayoutParams().height);
    }

    private BottomNavigationView navigation() { return activity.findViewById(R.id.bottomNavigation); }
    private void assertPage(int selected, int visible) {
        assertEquals(selected, navigation().getSelectedItemId());
        for (int page : new int[]{R.id.overviewPage, R.id.activityPage, R.id.rulesPage, R.id.devicesPage})
            assertEquals(page == visible ? View.VISIBLE : View.GONE, activity.findViewById(page).getVisibility());
    }
    private static void measure(View view, int widthDp, int heightDp) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        for (int pass = 0; pass < 2; pass++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }
    }
    private static void assertTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.length() > 0) {
                assertNotNull("Missing measured text: " + text.getText(), text.getLayout());
                assertTrue("Text clips vertically: " + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertEquals("Text must wrap: " + text.getText(), 0, text.getLayout().getEllipsisCount(line));
                    assertTrue("Text clips horizontally: " + text.getText(),
                        text.getLayout().getLineMax(line) <= text.getLayout().getWidth() + 1f);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int child = 0; child < group.getChildCount(); child++) assertTextFits(group.getChildAt(child));
        }
    }
}
