package tracewall.auth;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
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

    @Test public void initialScreenHasEveryProviderAndUncheckedSmsConsent() {
        launch(AuthActivity.ROLE_PARENT, null);
        assertNotNull(activity.findViewById(R.id.auth_email_submit));
        assertNotNull(activity.findViewById(R.id.auth_google));
        assertNotNull(activity.findViewById(R.id.auth_phone_send));
        assertFalse(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertFalse(activity.findViewById(R.id.auth_phone_send).isEnabled());
        assertNull(activity.getSupportActionBar());
        assertTrue(androidx.core.view.ViewCompat.isAccessibilityHeading(activity.findViewById(R.id.auth_title)));
        assertNull(auth.getCurrentUser());
    }

    @Test public void invalidEmailIsRejectedAndPasswordClearedBeforeAnySdkSignIn() {
        launch(AuthActivity.ROLE_CHILD, null);
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
        assertEquals(View.GONE, activity.findViewById(R.id.auth_mode_create).getVisibility());
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
        activity.findViewById(R.id.auth_mode_create).performClick();
        ((CheckBox) activity.findViewById(R.id.auth_sms_consent)).setChecked(true);
        input(R.id.auth_password).setText("synthetic-only-never-authenticated");
        input(R.id.auth_code).setText("0".repeat(6));
        android.content.res.Configuration changed = new android.content.res.Configuration(
            activity.getResources().getConfiguration());
        changed.orientation = changed.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? android.content.res.Configuration.ORIENTATION_PORTRAIT
            : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(changed);
        activity = controller.get();
        idle();
        assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.auth_mode_create)).isChecked());
        assertTrue(((CheckBox) activity.findViewById(R.id.auth_sms_consent)).isChecked());
        assertEquals("", input(R.id.auth_password).getText().toString());
        assertEquals("", input(R.id.auth_code).getText().toString());
        assertNull(auth.getCurrentUser());
    }
}
