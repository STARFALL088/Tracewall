package tracewall.child.ui;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import java.util.HashMap;
import java.util.Map;
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
import org.robolectric.util.ReflectionHelpers;
import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
import tracewall.security.PinStore;

/** Real view/page restoration; uses an in-memory PIN and never initializes Firebase or a VPN. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "en-rUS-w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class RulesNavigationTest {
    private ActivityController<RulesActivity> controller;
    private RulesActivity activity;

    @Before public void configureSyntheticPin() {
        Map<String, String> memory = new HashMap<>();
        PinStore pin = new PinStore(new PinStore.Storage() {
            public String get(String key) { return memory.get(key); }
            public void put(String key, String value) { memory.put(key, value); }
        });
        pin.set("2468"); // Synthetic fixture, not a user PIN or persisted credential.
        ReflectionHelpers.setStaticField(PinGate.class, "instance", pin);
    }

    @After public void destroy() {
        closeActivity();
        ReflectionHelpers.setStaticField(PinGate.class, "instance", null);
    }

    @Test public void shortcutsSelectSeparateDestinationsAndUnknownPagesFallBackToDomains() {
        assertEquals("rules_page", RulesActivity.EXTRA_PAGE);
        assertEquals(0, RulesActivity.PAGE_DOMAINS);
        assertEquals(1, RulesActivity.PAGE_SCHEDULES);
        assertEquals(2, RulesActivity.PAGE_BACKUP);
        for (int requested : new int[] {0, 1, 2, -1, Integer.MAX_VALUE}) {
            start(requested);
            assertPage(requested >= 0 && requested <= 2 ? requested : RulesActivity.PAGE_DOMAINS);
            assertNotNull(((MaterialToolbar) activity.findViewById(R.id.rules_back)).getNavigationIcon());
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            closeActivity();
        }
    }

    @Test public void hiddenDraftsWeekdaysCategoriesAndTimesSurviveRotationOnAnotherPage() {
        start(RulesActivity.PAGE_DOMAINS);
        String before = savedRules();
        field(R.id.rule_pattern).setText("*.study.example");
        field(R.id.rule_minutes).setText("45");
        activity.findViewById(R.id.domain_help_toggle).performClick();
        select(RulesActivity.PAGE_SCHEDULES);
        field(R.id.profile_name).setText("Study draft");
        field(R.id.profile_uids).setText("10001, 10002");
        check(R.id.profile_enabled).setChecked(false);
        int[] weekdays = {R.id.day_monday, R.id.day_tuesday, R.id.day_wednesday, R.id.day_thursday,
                R.id.day_friday, R.id.day_saturday, R.id.day_sunday};
        for (int i = 0; i < weekdays.length; i++) check(weekdays[i]).setChecked(i % 2 == 0);
        check(R.id.profile_ads).setChecked(false);
        check(R.id.profile_adult).setChecked(true);
        check(R.id.profile_gambling).setChecked(false);
        check(R.id.profile_malware).setChecked(true);
        chooseTime(R.id.profile_start, 22, 15);
        chooseTime(R.id.profile_end, 6, 30);
        activity.findViewById(R.id.schedule_help_toggle).performClick();
        select(RulesActivity.PAGE_BACKUP);

        controller.recreate();
        activity = controller.get();
        idle();
        assertPage(RulesActivity.PAGE_BACKUP);
        assertEquals("*.study.example", text(R.id.rule_pattern));
        assertEquals("45", text(R.id.rule_minutes));
        assertEquals("Study draft", text(R.id.profile_name));
        assertEquals("10001, 10002", text(R.id.profile_uids));
        assertFalse(check(R.id.profile_enabled).isChecked());
        for (int i = 0; i < weekdays.length; i++) assertEquals(i % 2 == 0, check(weekdays[i]).isChecked());
        assertFalse(check(R.id.profile_ads).isChecked());
        assertTrue(check(R.id.profile_adult).isChecked());
        assertFalse(check(R.id.profile_gambling).isChecked());
        assertTrue(check(R.id.profile_malware).isChecked());
        assertEquals(activity.getString(R.string.rules9_start, "22:15"), text(R.id.profile_start));
        assertEquals(activity.getString(R.string.rules9_end, "06:30"), text(R.id.profile_end));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.domain_help).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.schedule_help).getVisibility());
        select(RulesActivity.PAGE_SCHEDULES);
        assertPage(RulesActivity.PAGE_SCHEDULES);
        select(RulesActivity.PAGE_DOMAINS);
        assertPage(RulesActivity.PAGE_DOMAINS);
        assertEquals("*.study.example", text(R.id.rule_pattern));
        assertEquals(before, savedRules());
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test public void navigationDoesNotAuthorizeAnyDomainScheduleOrBackupAction() {
        start(RulesActivity.PAGE_DOMAINS);
        String before = savedRules();
        field(R.id.rule_pattern).setText("example.com");
        field(R.id.rule_minutes).setText("15");
        for (int action : new int[] {R.id.add_deny, R.id.add_permanent, R.id.add_timed}) assertPinPrompt(action);
        select(RulesActivity.PAGE_SCHEDULES);
        field(R.id.profile_name).setText("Homework");
        assertPinPrompt(R.id.save_profile);
        select(RulesActivity.PAGE_BACKUP);
        assertPinPrompt(R.id.export_rules);
        assertPinPrompt(R.id.import_rules);
        assertEquals(before, savedRules());
        assertNull(shadowOf(activity).getNextStartedActivity()); // No picker/share before authorization.
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test public void explicitToolbarBackFinishesTheEditorWithoutSavingADraft() {
        start(RulesActivity.PAGE_SCHEDULES);
        String before = savedRules();
        field(R.id.profile_name).setText("Unsaved schedule");
        MaterialToolbar toolbar = activity.findViewById(R.id.rules_back);
        View back = null;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (activity.getString(R.string.rules9_back).contentEquals(
                    child.getContentDescription() == null ? "" : child.getContentDescription())) back = child;
        }
        assertNotNull(back);
        assertTrue(back.performClick());
        assertTrue(activity.isFinishing());
        assertEquals(before, savedRules());
    }

    private void start(int page) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), RulesActivity.class)
                .putExtra(RulesActivity.EXTRA_PAGE, page);
        controller = Robolectric.buildActivity(RulesActivity.class, intent).setup();
        activity = controller.get();
        idle();
    }

    private void closeActivity() {
        if (controller != null) {
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    private void select(int page) {
        TabLayout tabs = activity.findViewById(R.id.rules_tabs);
        tabs.getTabAt(page).select();
        idle();
    }

    private void assertPage(int selected) {
        TabLayout tabs = activity.findViewById(R.id.rules_tabs);
        assertEquals(3, tabs.getTabCount());
        assertEquals(selected, tabs.getSelectedTabPosition());
        int[] pages = {R.id.rules_scroll, R.id.schedules_scroll, R.id.backup_scroll};
        for (int i = 0; i < pages.length; i++)
            assertEquals(i == selected ? View.VISIBLE : View.GONE, activity.findViewById(pages[i]).getVisibility());
    }

    private void chooseTime(int button, int hour, int minute) {
        activity.findViewById(button).performClick();
        TimePickerDialog picker = (TimePickerDialog) ShadowDialog.getLatestDialog();
        picker.updateTime(hour, minute);
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
    }

    private void assertPinPrompt(int action) {
        assertTrue(activity.findViewById(action).performClick());
        idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        assertNotNull(title);
        assertEquals(activity.getString(R.string.pin_required), title.getText().toString());
        dialog.findViewById(android.R.id.button2).performClick();
        idle();
    }

    private String savedRules() { return BlocklistStore.get().exportLocal(RuntimeEnvironment.getApplication()).serialize(); }
    private EditText field(int id) { return activity.findViewById(id); }
    private CheckBox check(int id) { return activity.findViewById(id); }
    private String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
