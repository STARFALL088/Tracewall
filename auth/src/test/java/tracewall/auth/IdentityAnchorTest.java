package tracewall.auth;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Android preference semantics only: no Firebase credentials, accounts or network calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class IdentityAnchorTest {
    private Context context;

    @Before public void cleanIdentity() {
        context = RuntimeEnvironment.getApplication();
        assertTrue(context.getSharedPreferences("tracewall.auth.identity", Context.MODE_PRIVATE)
            .edit().clear().commit());
    }

    @Test public void aFreshInstallationHasNoPrincipal() {
        assertNull(IdentityAnchor.get(context));
        assertFalse(IdentityAnchor.matches(context, null));
        assertFalse(IdentityAnchor.matches(context, "device-a"));
    }

    @Test public void theFirstPrincipalIsDurableAndRebindingItIsIdempotent() {
        assertTrue(IdentityAnchor.bind(context, "device-a"));
        assertTrue(IdentityAnchor.bind(context, "device-a"));
        assertEquals("device-a", context.getSharedPreferences("tracewall.auth.identity", Context.MODE_PRIVATE)
            .getString("bound_uid", null));
        assertTrue(IdentityAnchor.matches(context, "device-a"));
    }

    @Test public void anotherAccountCannotReplaceTheOriginalPrincipal() {
        assertTrue(IdentityAnchor.bind(context, "device-a"));
        assertFalse(IdentityAnchor.bind(context, "device-b"));
        assertEquals("device-a", IdentityAnchor.get(context));
        assertFalse(IdentityAnchor.matches(context, "device-b"));
        assertTrue(IdentityAnchor.matches(context, "device-a"));
    }

    @Test public void malformedBindingDoesNotAllocateOrReplaceAnIdentity() {
        assertFalse(IdentityAnchor.bind(context, null));
        assertFalse(IdentityAnchor.bind(context, ""));
        assertFalse(IdentityAnchor.bind(context, "x".repeat(128) + "x"));
        assertNull(IdentityAnchor.get(context));
        assertTrue(IdentityAnchor.bind(context, "device-a"));
        assertFalse(IdentityAnchor.bind(context, null));
        assertEquals("device-a", IdentityAnchor.get(context));
    }

    @Test public void identifiersAreComparedLiterallyNotNormalized() {
        assertTrue(IdentityAnchor.bind(context, "Device-A"));
        assertFalse(IdentityAnchor.matches(context, "device-a"));
        assertFalse(IdentityAnchor.matches(context, " Device-A "));
        assertEquals("Device-A", IdentityAnchor.get(context));
    }

    @Test public void concurrentBootstrapCannotBindTwoPrincipals() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> { start.await(); return IdentityAnchor.bind(context, "device-a"); });
            Future<Boolean> second = pool.submit(() -> { start.await(); return IdentityAnchor.bind(context, "device-b"); });
            start.countDown();
            boolean a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
            assertTrue(a ^ b);
            assertEquals(a ? "device-a" : "device-b", IdentityAnchor.get(context));
        } finally { pool.shutdownNow(); }
    }
}
