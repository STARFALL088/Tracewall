package tracewall.auth;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

/**
 * Shared, private authentication entry point for both applications.
 * Launch explicitly with EXTRA_ROLE and EXTRA_INITIAL; no credentials are returned in the result.
 */
public final class AuthActivity extends AppCompatActivity {
    public static final String EXTRA_ROLE = "tracewall.auth.role";
    public static final String ROLE_CHILD = "child";
    public static final String ROLE_PARENT = "parent";
    public static final String EXTRA_INITIAL = "tracewall.auth.initial";
    public static final String EXTRA_BOUND_UID = "tracewall.auth.bound_uid";

    private static final String STATE_METHOD = "tracewall.auth.method";
    private static final int METHOD_PICKER = 0;
    private static final int METHOD_EMAIL = 1;

    private AuthViewModel model;
    private EditText email;
    private EditText password;
    private RadioGroup emailModes;
    private String role;
    private boolean rendering;
    private int selectedMethod = METHOD_PICKER;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Credentials must not appear in screenshots, recents previews, or instance-state snapshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_tracewall_auth);
        if (savedInstanceState != null
                && savedInstanceState.getInt(STATE_METHOD, METHOD_PICKER) == METHOD_EMAIL) {
            selectedMethod = METHOD_EMAIL;
        }
        for (int heading : new int[] {R.id.auth_title, R.id.auth_method_heading,
                R.id.auth_email_section, R.id.auth_options_heading})
            androidx.core.view.ViewCompat.setAccessibilityHeading(findViewById(heading), true);
        setResult(RESULT_CANCELED);
        role = AccountPolicy.role(getIntent().getStringExtra(EXTRA_ROLE));
        email = findViewById(R.id.auth_email);
        password = findViewById(R.id.auth_password);
        emailModes = findViewById(R.id.auth_email_modes);
        // Redundant with XML intentionally: do not rely on a consuming app's input style defaults.
        for (EditText input : new EditText[] { email, password }) {
            input.setSaveEnabled(false);
            input.setSaveFromParentEnabled(false);
        }
        model = new ViewModelProvider(this).get(AuthViewModel.class);
        String durableUid = IdentityAnchor.get(this);
        model.initialize(role, getIntent().getBooleanExtra(EXTRA_INITIAL, false),
                durableUid != null ? durableUid : getIntent().getStringExtra(EXTRA_BOUND_UID));
        ((TextView) findViewById(R.id.auth_role)).setText(ROLE_PARENT.equals(role)
                ? R.string.auth_role_parent : R.string.auth_role_child);

        findViewById(R.id.auth_back).setOnClickListener(ignored -> navigateBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        findViewById(R.id.auth_select_email).setOnClickListener(ignored -> selectMethod(METHOD_EMAIL));
        emailModes.setOnCheckedChangeListener((group, checkedId) -> {
            if (!rendering) model.setCreateEmail(checkedId == R.id.auth_mode_create);
        });
        findViewById(R.id.auth_email_submit).setOnClickListener(ignored -> submitEmail());
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            AuthUiState state = model.states().getValue();
            if (state != null && state.emailLinked) confirmPassword(); else submitEmail();
            return true;
        });
        findViewById(R.id.auth_reauthenticate).setOnClickListener(ignored -> confirmPassword());
        findViewById(R.id.auth_reset).setOnClickListener(ignored -> model.resetPassword(text(email)));
        findViewById(R.id.auth_verify_email).setOnClickListener(ignored -> model.verifyEmail(false));
        findViewById(R.id.auth_refresh_email).setOnClickListener(ignored -> model.verifyEmail(true));
        findViewById(R.id.auth_guest).setOnClickListener(ignored -> { clearSecrets(); model.guest(); });
        findViewById(R.id.auth_local).setOnClickListener(ignored -> leave(true));
        model.states().observe(this, this::render);
    }

    private static String text(EditText view) { return view.getText() == null ? "" : view.getText().toString(); }

    private void submitEmail() {
        String secret = text(password); // Do not trim passwords or store them beyond this call.
        password.setText("");
        model.submitEmail(text(email), secret);
    }

    private void confirmPassword() {
        String secret = text(password);
        password.setText("");
        model.confirmEmailPassword(secret);
    }

    private void render(AuthUiState state) {
        if (isFinishing()) return;
        rendering = true;
        boolean editable = state.configured && !state.identityMismatch && !state.busy;
        boolean choosingMethod = selectedMethod == METHOD_PICKER;
        visible(R.id.auth_method_picker, choosingMethod);
        visible(R.id.auth_email_form, selectedMethod == METHOD_EMAIL);
        enabled(R.id.auth_select_email, !state.busy);
        ((TextView) findViewById(R.id.auth_back)).setText(choosingMethod || state.busy
                ? R.string.auth_back : R.string.auth_other_methods);
        ((TextView) findViewById(R.id.auth_title)).setText(state.hasUser
                ? R.string.auth_title_upgrade : R.string.auth_title_initial);
        ((TextView) findViewById(R.id.auth_status)).setText(!state.hasUser
                ? state.boundIdentity ? R.string.auth_bound_recovery : R.string.auth_intro
                : state.anonymous ? R.string.auth_status_guest : R.string.auth_status_account);
        visible(R.id.auth_progress, state.busy);
        visible(R.id.auth_message, state.message != 0);
        if (state.message != 0) ((TextView) findViewById(R.id.auth_message)).setText(state.message);

        visible(R.id.auth_email_modes, !state.hasUser && !state.boundIdentity);
        visible(R.id.auth_mode_create, !state.boundIdentity);
        emailModes.check(state.createEmail ? R.id.auth_mode_create : R.id.auth_mode_sign_in);
        enabled(R.id.auth_mode_create, editable && !state.boundIdentity);
        enabled(R.id.auth_mode_sign_in, editable);
        email.setEnabled(editable && !state.emailLinked);
        password.setEnabled(editable);
        if (state.emailLinked && !text(email).equals(state.email)) email.setText(state.email);
        Button emailButton = findViewById(R.id.auth_email_submit);
        emailButton.setText(state.emailLinked ? R.string.auth_email_connected : state.hasUser ? R.string.auth_link_email
                : state.createEmail ? R.string.auth_create_account : R.string.auth_sign_in);
        emailButton.setEnabled(editable && !state.emailLinked);
        visible(R.id.auth_email_submit, !state.emailLinked);
        visible(R.id.auth_email_connection, state.emailLinked);
        ((TextView) findViewById(R.id.auth_password_help)).setText(state.emailLinked
                ? R.string.auth_reauthenticate_help : state.hasUser || state.createEmail
                ? R.string.auth_password_help : R.string.auth_email_sign_in_help);
        visible(R.id.auth_reset, !state.createEmail || state.hasUser || state.boundIdentity);
        enabled(R.id.auth_reset, editable);
        visible(R.id.auth_reauthenticate, state.emailLinked);
        enabled(R.id.auth_reauthenticate, editable && state.emailLinked);

        visible(R.id.auth_email_verification_group, state.hasEmail);
        visible(R.id.auth_email_verification, state.hasEmail);
        ((TextView) findViewById(R.id.auth_email_verification)).setText(state.emailVerified
                ? R.string.auth_email_verified : R.string.auth_email_unverified);
        visible(R.id.auth_verify_email, state.hasEmail && !state.emailVerified);
        visible(R.id.auth_refresh_email, state.hasEmail && !state.emailVerified);
        enabled(R.id.auth_verify_email, editable);
        enabled(R.id.auth_refresh_email, editable);

        visible(R.id.auth_initial_options, choosingMethod && (!state.hasUser || state.identityMismatch));
        visible(R.id.auth_guest, !state.hasUser && !state.boundIdentity);
        enabled(R.id.auth_guest, editable && !state.boundIdentity);
        visible(R.id.auth_guest_help, !state.hasUser && !state.boundIdentity);
        // Local mode remains available without configuration/network; no fabricated auth result.
        enabled(R.id.auth_local, !state.busy);
        visible(R.id.auth_identity_lock_notice, state.hasUser || state.boundIdentity);
        rendering = false;

        if (!state.hasUser || state.identityMismatch) setResult(RESULT_CANCELED);
        else if (state.successSequence > 0) {
            setResult(RESULT_OK);
            if (state.autoFinish) { clearSecrets(); finish(); }
        }
    }

    private void visible(int id, boolean visible) { findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE); }
    private void enabled(int id, boolean enabled) { findViewById(id).setEnabled(enabled); }

    private void selectMethod(int method) {
        AuthUiState state = model.states().getValue();
        // Hidden/programmatically-clicked navigation cannot interrupt owned authentication work.
        if (state == null || state.busy) return;
        clearSecrets();
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        selectedMethod = method;
        AuthUiState current = model.states().getValue();
        if (current != null) render(current);
        ((android.widget.ScrollView) findViewById(R.id.auth_scroll)).scrollTo(0, 0);
    }

    private void navigateBack() {
        AuthUiState state = model.states().getValue();
        if (selectedMethod != METHOD_PICKER && state != null && !state.busy) {
            selectMethod(METHOD_PICKER);
        } else {
            leave(false);
        }
    }

    private void leave(boolean locally) {
        if (!model.mayLeave()) return;
        clearSecrets();
        if (locally) setResult(RESULT_CANCELED);
        finish();
    }

    private void clearSecrets() {
        if (password != null) password.setText("");
    }

    @Override protected void onStop() {
        clearSecrets();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        clearSecrets();
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_METHOD, selectedMethod);
        // No credential or account secret is ever added to this Bundle.
    }

    @Override protected void onDestroy() {
        clearSecrets();
        super.onDestroy();
    }
}
