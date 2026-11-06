package tracewall.auth;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
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

/** Real Android/Firebase SDK UI objects with inert config and loopback-only Auth networking.
 * No user/account/credential response is fabricated and no real authentication is attempted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class AuthActivityTest {
    private ActivityController<AuthActivity> controller;
    private AuthActivity activity;
    private FirebaseApp firebase;
    private FirebaseAuth auth;

    @Before public void configureInertSdk() {
        Application application = RuntimeEnvironment.getApplication();
        assertTrue(application.getSharedPreferences("tracewall.auth.identity", Application.MODE_PRIVATE)
            .edit().clear().commit());
        for (FirebaseApp existing : FirebaseApp.getApps(application)) existing.delete();
        FirebaseOptions options = new FirebaseOptions.Builder()
            .setProjectId("demo-tracewall-auth")
            .setApplicationId("1:123456789012:android:" + "a".repeat(32))
            .setApiKey("AIzaSy" + "0".repeat(33))
            .build();
        firebase = FirebaseApp.initializeApp(application, options);
        auth = FirebaseAuth.getInstance(firebase);
        auth.useEmulator("127.0.0.1", 9099);
        assertNull(auth.getCurrentUser());
    }

    @After public void closeUiAndSdk() {
        if (controller != null) controller.pause().stop().destroy();
        if (firebase != null) firebase.delete();
    }

    private void launch(String role, String bound) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AuthActivity.class)
            .putExtra(AuthActivity.EXTRA_ROLE, role)
            .putExtra(AuthActivity.EXTRA_BOUND_UID, bound);
        controller = Robolectric.buildActivity(AuthActivity.class, intent).setup();
        activity = controller.get();
        idle();
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private EditText input(int id) { return activity.findViewById(id); }
    private String message() { return ((TextView) activity.findViewById(R.id.auth_message)).getText().toString(); }
    private void chooseEmail() { activity.findViewById(R.id.auth_select_email).performClick(); idle(); }
    private void choosePhone() { activity.findViewById(R.id.auth_select_phone).performClick(); idle(); }

    private void rotate() {
        android.content.res.Configuration changed = new android.content.res.Configuration(
            activity.getResources().getConfiguration());
        changed.orientation = changed.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? android.content.res.Configuration.ORIENTATION_PORTRAIT
            : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(changed);
        activity = controller.get();
        idle();
    }

    @Test public void initialScreenHasEveryProviderAndUncheckedSmsConsent() {
        launch(AuthActivity.ROLE_PARENT, null);
        assertNotNull(activity.findViewById(R.id.auth_email_submit));
        assertNotNull(activity.findViewById(R.id.auth_google));
        assertNotNull(activity.findViewById(R.id.auth_phone_send));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_method_picker).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_initial_options).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_guest).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_local).getVisibility());
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertFalse(activity.findViewById(R.id.auth_phone_send).isEnabled());
        assertNull(activity.getSupportActionBar());
        assertTrue(androidx.core.view.ViewCompat.isAccessibilityHeading(activity.findViewById(R.id.auth_title)));
        assertNull(auth.getCurrentUser());
    }

    @Test public void invalidEmailIsRejectedAndPasswordClearedBeforeAnySdkSignIn() {
        launch(AuthActivity.ROLE_CHILD, null);
        chooseEmail();
        input(R.id.auth_email).setText("not-an-address");
        input(R.id.auth_password).setText("synthetic-not-a-real-password");
        activity.findViewById(R.id.auth_email_submit).performClick();
        idle();
        assertEquals(activity.getString(R.string.auth_error_email), message());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test public void shortNewPasswordNeverStartsAccountCreation() {
        launch(AuthActivity.ROLE_PARENT, null);
        chooseEmail();
        activity.findViewById(R.id.auth_mode_create).performClick();
        input(R.id.auth_email).setText("unit@example.invalid");
        input(R.id.auth_password).setText("x".repeat(7));
        activity.findViewById(R.id.auth_email_submit).performClick();
        idle();
        assertEquals(activity.getString(R.string.auth_error_password_short), message());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test public void smsConsentAndPhoneSyntaxAreBothEnforcedWithoutSendingSms() {
        launch(AuthActivity.ROLE_CHILD, null);
        choosePhone();
        // A programmatic click must not bypass the independent ViewModel consent guard.
        activity.findViewById(R.id.auth_phone_send).performClick();
        idle();
        assertEquals(activity.getString(R.string.auth_error_consent), message());
        ((CheckBox) activity.findViewById(R.id.auth_sms_consent)).setChecked(true);
        input(R.id.auth_phone).setText("not-an-e164-number");
        activity.findViewById(R.id.auth_phone_send).performClick();
        idle();
        assertEquals(activity.getString(R.string.auth_error_phone), message());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_code_group).getVisibility());
        assertNull(auth.getCurrentUser());
    }

    @Test public void unconfiguredGoogleFailsClearlyWithoutAuthenticating() {
        launch(AuthActivity.ROLE_PARENT, null);
        activity.findViewById(R.id.auth_google).performClick();
        idle();
        assertTrue(message().equals(activity.getString(R.string.auth_error_google_setup))
            || message().equals(activity.getString(R.string.auth_google_unavailable)));
        assertTrue(activity.findViewById(R.id.auth_google).isEnabled());
        assertNull(auth.getCurrentUser());
    }

    @Test public void localEntryDoesNotCreateAnAnonymousUserOrClaimSuccess() {
        launch(AuthActivity.ROLE_CHILD, null);
        activity.findViewById(R.id.auth_local).performClick();
        idle();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertNull(shadowOf(activity).getResultIntent());
        assertNull(auth.getCurrentUser());
    }

    @Test public void aBoundInstallationCannotCreateAReplacementGuestOrEmailAccount() {
        assertTrue(IdentityAnchor.bind(RuntimeEnvironment.getApplication(), "original-device-uid"));
        // The private screen must honor the durable anchor even if a future caller omits the extra.
        launch(AuthActivity.ROLE_PARENT, null);
        assertFalse(activity.findViewById(R.id.auth_guest).isEnabled());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_guest).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_guest_help).getVisibility());
        assertEquals(activity.getString(R.string.auth_bound_recovery),
            ((TextView) activity.findViewById(R.id.auth_status)).getText().toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_identity_lock_notice).getVisibility());
        assertTrue(activity.findViewById(R.id.auth_local).isEnabled());
        chooseEmail();
        assertEquals(View.GONE, activity.findViewById(R.id.auth_mode_create).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_modes).getVisibility());
        activity.findViewById(R.id.auth_guest).performClick();
        idle();
        assertEquals(activity.getString(R.string.auth_bound_recovery), message());
        assertNull(auth.getCurrentUser());
    }

    @Test public void credentialViewsCannotSaveAndScreenIsExcludedFromCapture() {
        launch(AuthActivity.ROLE_CHILD, null);
        for (int id : new int[] {R.id.auth_email, R.id.auth_password, R.id.auth_phone, R.id.auth_code}) {
            assertFalse(input(id).isSaveEnabled());
            assertFalse(input(id).isSaveFromParentEnabled());
        }
        assertNotEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        input(R.id.auth_password).setText("synthetic-only-never-authenticated");
        input(R.id.auth_code).setText("0".repeat(6));
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertEquals("", input(R.id.auth_code).getText().toString());
    }

    @Test public void unknownCallerRoleDefaultsToProtectedChild() {
        launch("not-a-valid-role", null);
        assertEquals(activity.getString(R.string.auth_role_child),
            ((TextView) activity.findViewById(R.id.auth_role)).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test @Config(sdk = 24) public void theMinimumSupportedSdkRendersCompatibleHeadings() {
        launch(AuthActivity.ROLE_CHILD, null);
        for (int id : new int[] {R.id.auth_title, R.id.auth_email_section, R.id.auth_phone_section})
            assertTrue(androidx.core.view.ViewCompat.isAccessibilityHeading(activity.findViewById(id)));
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertNull(auth.getCurrentUser());
    }

    @Test public void recreationRetainsNonSecretFlowChoicesButClearsSecrets() {
        launch(AuthActivity.ROLE_PARENT, null);
        chooseEmail();
        activity.findViewById(R.id.auth_mode_create).performClick();
        activity.findViewById(R.id.auth_back).performClick();
        choosePhone();
        ((CheckBox) activity.findViewById(R.id.auth_sms_consent)).setChecked(true);
        input(R.id.auth_phone).setText("not-an-active-phone-request");
        input(R.id.auth_password).setText("synthetic-only-never-authenticated");
        input(R.id.auth_code).setText("0".repeat(6));
        rotate();
        assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.auth_mode_create)).isChecked());
        assertTrue(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_method_picker).getVisibility());
        assertEquals("", input(R.id.auth_phone).getText().toString());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertEquals("", input(R.id.auth_code).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test public void methodPagesAreExclusiveAndBackRestoresGuestAndLocalChoices() {
        launch(AuthActivity.ROLE_CHILD, null);
        chooseEmail();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_initial_options).getVisibility());
        input(R.id.auth_password).setText("synthetic-only-not-submitted");
        activity.findViewById(R.id.auth_back).performClick();
        idle();
        assertFalse(activity.isFinishing());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_method_picker).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_initial_options).getVisibility());
        choosePhone();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_form).getVisibility());
        ((CheckBox) activity.findViewById(R.id.auth_sms_consent)).setChecked(true);
        input(R.id.auth_code).setText("0".repeat(6));
        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertEquals("", input(R.id.auth_code).getText().toString());
        choosePhone();
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertFalse(activity.findViewById(R.id.auth_phone_send).isEnabled());
        assertNull(auth.getCurrentUser());
    }

    @Test public void emailPageAndCreationChoiceSurviveRotationWithoutSavingInputs() {
        launch(AuthActivity.ROLE_PARENT, null);
        chooseEmail();
        activity.findViewById(R.id.auth_mode_create).performClick();
        input(R.id.auth_email).setText("unit@example.invalid");
        input(R.id.auth_password).setText("synthetic-only-not-submitted");
        rotate();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.auth_mode_create)).isChecked());
        assertEquals("", input(R.id.auth_email).getText().toString());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_reset).getVisibility());
        assertEquals(activity.getString(R.string.auth_password_help),
            ((TextView) activity.findViewById(R.id.auth_password_help)).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test public void retainedPhoneUiOverridesEmailNavigationAndCancelKeepsCooldown() {
        launch(AuthActivity.ROLE_CHILD, null);
        chooseEmail();
        AuthViewModel retained = new ViewModelProvider(activity).get(AuthViewModel.class);
        // UI-only retained-flow fixture. No provider callback, SMS, credential or auth result is faked.
        PhoneFlowGuard guard = ReflectionHelpers.getField(retained, "phoneFlow");
        long generation = guard.start(SystemClock.elapsedRealtime());
        guard.stopListening(generation); // Rotation must not issue any SDK request in this test.
        String fictionalPhone = "+" + "1" + "202" + "555" + "0107";
        ReflectionHelpers.setField(retained, "phone", fictionalPhone);
        ReflectionHelpers.setField(retained, "consent", true);
        ReflectionHelpers.setField(retained, "verificationId", "ui-only-verification-marker");
        retained.show(R.string.auth_code_sent);
        idle();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_code_group).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_send).getVisibility());
        assertFalse(activity.findViewById(R.id.auth_select_email).isEnabled());
        chooseEmail(); // A hidden/programmatic click cannot strand an active verification session.
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        input(R.id.auth_code).setText("0".repeat(6));
        rotate();
        assertSame(retained, new ViewModelProvider(activity).get(AuthViewModel.class));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_code_group).getVisibility());
        assertEquals(fictionalPhone, input(R.id.auth_phone).getText().toString());
        assertEquals("", input(R.id.auth_code).getText().toString());
        assertFalse(input(R.id.auth_phone).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_resend).isEnabled());
        assertTrue(activity.findViewById(R.id.auth_phone_cancel).isEnabled());
        activity.findViewById(R.id.auth_phone_cancel).performClick();
        idle();
        assertEquals(View.GONE, activity.findViewById(R.id.auth_code_group).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_cooldown).getVisibility());
        assertTrue(retained.states().getValue().cooldownSeconds > 0);
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertFalse(activity.findViewById(R.id.auth_phone_send).isEnabled());
        assertEquals("", input(R.id.auth_phone).getText().toString());
        assertNull(auth.getCurrentUser());
    }

    @Test public void methodNavigationDoesNotBypassBusyLeaveGuard() {
        launch(AuthActivity.ROLE_PARENT, null);
        chooseEmail();
        AuthViewModel retained = new ViewModelProvider(activity).get(AuthViewModel.class);
        AuthFlowGuard guard = ReflectionHelpers.getField(retained, "work");
        long token = guard.begin(); // Exercise the real ownership guard, without an SDK operation.
        retained.show(R.string.auth_working);
        choosePhone();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertFalse(activity.isFinishing());
        assertEquals(activity.getString(R.string.auth_wait_to_leave), message());
        assertFalse(activity.findViewById(R.id.auth_email_submit).isEnabled());
        assertTrue(guard.finish(token));
        retained.show(0);
        activity.findViewById(R.id.auth_back).performClick();
        idle();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_method_picker).getVisibility());
        assertNull(auth.getCurrentUser());
    }

    @Test public void connectedEmailKeepsConfirmationAndVerificationActionsOnEmailPage() {
        launch(AuthActivity.ROLE_PARENT, null);
        chooseEmail();
        renderEmailAccount(false, false);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_email_connection).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_submit).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_reauthenticate).getVisibility());
        assertTrue(activity.findViewById(R.id.auth_reauthenticate).isEnabled());
        assertEquals("unit@example.invalid", input(R.id.auth_email).getText().toString());
        assertFalse(input(R.id.auth_email).isEnabled());
        assertTrue(input(R.id.auth_password).isEnabled());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_verify_email).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_refresh_email).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_identity_lock_notice).getVisibility());
        renderEmailAccount(true, false);
        assertEquals(View.GONE, activity.findViewById(R.id.auth_verify_email).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.auth_refresh_email).getVisibility());
        assertEquals(activity.getString(R.string.auth_email_verified),
            ((TextView) activity.findViewById(R.id.auth_email_verification)).getText().toString());
        renderEmailAccount(false, true);
        assertFalse(input(R.id.auth_password).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_reauthenticate).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_verify_email).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_refresh_email).isEnabled());
        assertNull(auth.getCurrentUser());
    }

    private void renderEmailAccount(boolean verified, boolean mismatch) {
        // Renderer contract only, not a fabricated FirebaseUser or successful authentication response.
        AuthUiState state = new AuthUiState(true, mismatch, true, true, false,
            true, false, false, true, verified, "unit@example.invalid", false,
            false, false, false, false, 0L, false, "", false, 0, 0L, false);
        ReflectionHelpers.callInstanceMethod(activity, "render",
            ReflectionHelpers.ClassParameter.from(AuthUiState.class, state));
    }

    @Test public void providerSetupFailureStillOffersLocalModeWithoutAnAccount() {
        firebase.delete();
        firebase = null;
        launch(AuthActivity.ROLE_CHILD, null);
        assertEquals(activity.getString(R.string.auth_error_setup), message());
        assertTrue(activity.findViewById(R.id.auth_local).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_google).isEnabled());
        choosePhone();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        assertFalse(input(R.id.auth_phone).isEnabled());
        assertFalse(activity.findViewById(R.id.auth_phone_send).isEnabled());
        activity.findViewById(R.id.auth_back).performClick();
        activity.findViewById(R.id.auth_local).performClick();
        idle();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertTrue(FirebaseApp.getApps(RuntimeEnvironment.getApplication()).isEmpty());
    }

    @Test public void credentialInputTypesAndAutofillExclusionArePreserved() {
        launch(AuthActivity.ROLE_CHILD, null);
        assertEquals(InputType.TYPE_CLASS_TEXT, input(R.id.auth_email).getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            input(R.id.auth_email).getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            input(R.id.auth_password).getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(InputType.TYPE_CLASS_PHONE, input(R.id.auth_phone).getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_CLASS_NUMBER, input(R.id.auth_code).getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            input(R.id.auth_code).getInputType() & InputType.TYPE_MASK_VARIATION);
        for (int id : new int[] {R.id.auth_email, R.id.auth_password, R.id.auth_phone, R.id.auth_code})
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input(id).getImportantForAutofill());
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            activity.findViewById(R.id.auth_scroll).getImportantForAutofill());
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h480dp")
    public void englishMethodPagesScrollWithoutClippingAtLargeFontSize() {
        assertNarrowLargeFontPages();
    }

    @Test @Config(qualifiers = "bn-rBD-w320dp-h480dp")
    public void bengaliMethodPagesScrollWithoutClippingAtLargeFontSize() {
        assertNarrowLargeFontPages();
    }

    private void assertNarrowLargeFontPages() {
        RuntimeEnvironment.setFontScale(2f);
        launch(AuthActivity.ROLE_CHILD, null);
        layoutAt(320, 480);
        assertVisibleContentFits(activity.findViewById(R.id.auth_content));
        ScrollView scroll = activity.findViewById(R.id.auth_scroll);
        assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scroll.setSmoothScrollingEnabled(false);
        scroll.fullScroll(View.FOCUS_DOWN);
        assertTrue(scroll.getScrollY() > 0);
        chooseEmail();
        layoutAt(320, 480);
        assertVisibleContentFits(activity.findViewById(R.id.auth_content));
        assertEquals(View.GONE, activity.findViewById(R.id.auth_phone_form).getVisibility());
        activity.findViewById(R.id.auth_back).performClick();
        choosePhone();
        layoutAt(320, 480);
        assertVisibleContentFits(activity.findViewById(R.id.auth_content));
        assertEquals(View.GONE, activity.findViewById(R.id.auth_email_form).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_sms_consent).getVisibility());
        assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
    }

    @Test @Config(qualifiers = "en-rUS-w840dp-h900dp")
    public void readableColumnIsCappedOnTabletAndShrinksForNarrowWindows() {
        launch(AuthActivity.ROLE_PARENT, null);
        layoutAt(840, 900);
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(600 * density), activity.findViewById(R.id.auth_content).getWidth());
        layoutAt(280, 480);
        assertEquals(Math.round(280 * density), activity.findViewById(R.id.auth_content).getWidth());
        assertVisibleContentFits(activity.findViewById(R.id.auth_content));
    }

    private void layoutAt(int widthDp, int heightDp) {
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        ScrollView scroll = activity.findViewById(R.id.auth_scroll);
        // A second pass applies the column width selected by the real on-layout listener.
        for (int pass = 0; pass < 2; pass++) {
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, width, height);
        }
    }

    private static void assertVisibleContentFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.length() > 0) {
                assertNotNull("Visible text must have a measured layout: " + text.getText(), text.getLayout());
                assertTrue("Text clipped vertically: " + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertEquals("Text must wrap rather than ellipsize: " + text.getText(),
                        0, text.getLayout().getEllipsisCount(line));
                    assertTrue("Text clipped horizontally: " + text.getText(),
                        text.getLayout().getLineMax(line) <= text.getLayout().getWidth() + 1f);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child.getVisibility() == View.VISIBLE) {
                    assertTrue("Content overflows the left edge", child.getLeft() >= group.getPaddingLeft());
                    assertTrue("Content overflows the right edge",
                        child.getRight() <= group.getWidth() - group.getPaddingRight());
                    assertVisibleContentFits(child);
                }
            }
        }
    }
}
