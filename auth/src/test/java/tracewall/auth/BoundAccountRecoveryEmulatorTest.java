package tracewall.auth;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Firebase Android SDK against a real loopback Auth emulator; never a production project. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BoundAccountRecoveryEmulatorTest {
    private static final String PROJECT = "demo-tracewall";
    private static final String PASSWORD = "synthetic-emulator-only-password";
    private Application application;
    private FirebaseAuth primary;
    private Executor executor;
    private int port;
    private final List<String> primaryStates = new ArrayList<>();

    @Before public void setUp() {
        assertEquals("true", System.getProperty("tracewall.auth.emulator"));
        application = RuntimeEnvironment.getApplication();
        for (FirebaseApp app : FirebaseApp.getApps(application)) app.delete();
        port = Integer.parseInt(System.getProperty("tracewall.auth.emulator.port", "9099"));
        assertTrue(port >= 1024 && port <= 65535);
        FirebaseOptions options = new FirebaseOptions.Builder().setProjectId(PROJECT)
            .setApplicationId("1:123456789012:android:" + "a".repeat(32))
            .setApiKey("AIza" + "0".repeat(35)).build();
        primary = emulator(FirebaseApp.initializeApp(application, options));
        primary.signOut();
        executor = ContextCompat.getMainExecutor(application);
        primary.addAuthStateListener(auth -> primaryStates.add(auth.getCurrentUser() == null
            ? "<none>" : auth.getCurrentUser().getUid()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void tearDown() {
        for (FirebaseApp app : FirebaseApp.getApps(application)) {
            FirebaseAuth.getInstance(app).signOut();
            app.delete();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private FirebaseAuth emulator(FirebaseApp app) {
        FirebaseAuth auth = FirebaseAuth.getInstance(app);
        auth.useEmulator("127.0.0.1", port);
        return auth;
    }

    private <T> Task<T> finished(Task<T> task) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!task.isComplete() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(20);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Real SDK/emulator operation timed out", task.isComplete());
        return task;
    }

    private <T> T success(Task<T> task) throws Exception {
        finished(task);
        if (!task.isSuccessful()) throw new AssertionError("Real SDK/emulator operation failed", task.getException());
        return task.getResult();
    }

    private String seed(String email) throws Exception {
        FirebaseApp app = FirebaseApp.initializeApp(application, primary.getApp().getOptions(), "fixture");
        FirebaseAuth fixture = emulator(app);
        String uid = success(fixture.createUserWithEmailAndPassword(email, PASSWORD)).getUser().getUid();
        fixture.signOut();
        app.delete();
        return uid;
    }

    private Task<AuthResult> recovery(AuthCredential credential, String boundUid) {
        return BoundAccountRecovery.signIn(primary, credential, boundUid, () -> true, executor, this::emulator);
    }

    @Test public void aGuestUpgradeKeepsItsActualFirebaseUid() throws Exception {
        String uid = success(primary.signInAnonymously()).getUser().getUid();
        assertTrue(primary.getCurrentUser().isAnonymous());
        String email = "linked-" + System.nanoTime() + "@example.invalid";
        AuthResult linked = success(primary.getCurrentUser().linkWithCredential(
            EmailAuthProvider.getCredential(email, PASSWORD)));
        assertEquals(uid, linked.getUser().getUid());
        assertEquals(uid, primary.getCurrentUser().getUid());
        assertFalse(primary.getCurrentUser().isAnonymous());
    }

    @Test public void aRealCredentialCollisionCannotReplaceTheGuest() throws Exception {
        String email = "collision-" + System.nanoTime() + "@example.invalid";
        String otherUid = seed(email);
        String uid = success(primary.signInAnonymously()).getUser().getUid();
        Task<AuthResult> linked = finished(primary.getCurrentUser().linkWithCredential(
            EmailAuthProvider.getCredential(email, PASSWORD)));
        assertFalse(linked.isSuccessful());
        assertEquals(uid, primary.getCurrentUser().getUid());
        assertTrue(primary.getCurrentUser().isAnonymous());
        assertFalse(primaryStates.contains(otherUid));
    }

    @Test public void wrongCandidateNeverBecomesThePrimarySdkPrincipal() throws Exception {
        String email = "wrong-" + System.nanoTime() + "@example.invalid";
        String wrongUid = seed(email);
        Task<AuthResult> result = finished(recovery(EmailAuthProvider.getCredential(email, PASSWORD), "other-anchor"));
        assertFalse(result.isSuccessful());
        assertTrue(result.getException() instanceof BoundAccountRecovery.Failure);
        assertEquals(BoundAccountRecovery.Reason.WRONG_IDENTITY,
            ((BoundAccountRecovery.Failure) result.getException()).reason);
        assertNull(primary.getCurrentUser());
        assertFalse(primaryStates.contains(wrongUid));
        assertEquals(1, FirebaseApp.getApps(application).size());
    }

    @Test public void exactCandidateIsCopiedAndSecondarySessionIsDisposed() throws Exception {
        String email = "correct-" + System.nanoTime() + "@example.invalid";
        String uid = seed(email);
        AuthResult recovered = success(recovery(EmailAuthProvider.getCredential(email, PASSWORD), uid));
        assertEquals(uid, recovered.getUser().getUid());
        assertEquals(uid, primary.getCurrentUser().getUid());

        assertTrue(primaryStates.stream().allMatch(value -> value.equals("<none>") || value.equals(uid)));
        assertEquals(1, FirebaseApp.getApps(application).size());
        assertNotNull(success(primary.getCurrentUser().getIdToken(true)).getToken());
    }

    @Test public void aRestoredSecondarySessionIsClearedWithoutTouchingThePrimary() throws Exception {
        String staleEmail = "restored-" + System.nanoTime() + "@example.invalid";
        String staleUid = seed(staleEmail);
        String email = "recovered-" + System.nanoTime() + "@example.invalid";
        String uid = seed(email);
        FirebaseApp previous = FirebaseApp.initializeApp(application, primary.getApp().getOptions(),
            BoundAccountRecovery.APP_NAME);
        FirebaseAuth previousAuth = emulator(previous);
        success(previousAuth.signInWithEmailAndPassword(staleEmail, PASSWORD));
        previous.delete(); // Simulate an interrupted secondary session, retaining its SDK storage.
        success(recovery(EmailAuthProvider.getCredential(email, PASSWORD), uid));
        assertEquals(uid, primary.getCurrentUser().getUid());
        assertFalse(primaryStates.contains(staleUid));
        assertEquals(1, FirebaseApp.getApps(application).size());
    }

    @Test public void lostOperationOwnershipNeverPromotesTheVerifiedCandidate() throws Exception {
        String email = "stale-" + System.nanoTime() + "@example.invalid";
        String uid = seed(email);
        AtomicInteger checks = new AtomicInteger();
        Task<AuthResult> result = finished(BoundAccountRecovery.signIn(primary,
            EmailAuthProvider.getCredential(email, PASSWORD), uid,
            () -> checks.getAndIncrement() == 0, executor, this::emulator));
        assertFalse(result.isSuccessful());
        assertEquals(BoundAccountRecovery.Reason.STALE,
            ((BoundAccountRecovery.Failure) result.getException()).reason);
        assertNull(primary.getCurrentUser());
        assertFalse(primaryStates.contains(uid));
        assertEquals(1, FirebaseApp.getApps(application).size());
    }

    @Test public void anExistingPrimarySessionCannotBeReplaced() throws Exception {
        String email = "existing-" + System.nanoTime() + "@example.invalid";
        String uid = success(primary.createUserWithEmailAndPassword(email, PASSWORD)).getUser().getUid();
        Task<AuthResult> result = finished(recovery(EmailAuthProvider.getCredential(email, PASSWORD), uid));
        assertFalse(result.isSuccessful());
        assertEquals(uid, primary.getCurrentUser().getUid());
        assertEquals(1, FirebaseApp.getApps(application).size());
    }

    @Test public void verifiedPhoneUserIsCopiedWithoutConsumingTheSmsCredentialTwice() throws Exception {
        // Reserved fictional NANP range; the emulator never delivers a real SMS.
        String phone = "+1" + "202" + "555" + "0149";
        JSONObject first = phoneCode(phone);
        JSONObject account = http("POST", "/identitytoolkit.googleapis.com/v1/accounts:signInWithPhoneNumber?key=emulator",
            new JSONObject().put("sessionInfo", first.getString("sessionInfo")).put("code", first.getString("code")));
        String uid = account.getString("localId");
        JSONObject second = phoneCode(phone);
        success(recovery(PhoneAuthProvider.getCredential(second.getString("sessionInfo"), second.getString("code")), uid));
        assertEquals(uid, primary.getCurrentUser().getUid());
        assertEquals(phone, primary.getCurrentUser().getPhoneNumber());
        assertEquals(1, FirebaseApp.getApps(application).size());
        assertNotNull(success(primary.getCurrentUser().getIdToken(true)).getToken());
    }

    private JSONObject phoneCode(String phone) throws Exception {
        JSONObject sent = http("POST", "/identitytoolkit.googleapis.com/v1/accounts:sendVerificationCode?key=emulator",
            new JSONObject().put("phoneNumber", phone));
        JSONArray codes = http("GET", "/emulator/v1/projects/" + PROJECT + "/verificationCodes", null)
            .getJSONArray("verificationCodes");
        for (int i = 0; i < codes.length(); i++) {
            JSONObject value = codes.getJSONObject(i);
            if (sent.getString("sessionInfo").equals(value.getString("sessionInfo"))) return value;
        }
        throw new AssertionError("Emulator did not expose its fictional verification session");
    }

    private JSONObject http(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer owner");
        try {
            if (body != null) {
                connection.setDoOutput(true);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            assertEquals("Local Auth emulator HTTP status", 200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
