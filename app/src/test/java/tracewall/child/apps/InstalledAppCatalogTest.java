package tracewall.child.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class InstalledAppCatalogTest {
    private Application app;
    private ShadowPackageManager packages;
    private int originalUid;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        packages = shadowOf(app.getPackageManager());
        originalUid = app.getApplicationInfo().uid;
        app.getApplicationInfo().uid = 15_000;
    }

    @After public void tearDown() {
        app.getApplicationInfo().uid = originalUid;
    }

    @Test public void scanUsesNamesAndExcludesSystemPlatformAndControlPlaneUids() {
        install("com.example.zebra", "Zebra", 12_001, 0);
        install("com.example.alpha", "alpha", 12_002, 0);
        install("com.example.updated", "Beta", 12_003,
                ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        install("com.example.system", "System only", 12_004, ApplicationInfo.FLAG_SYSTEM);
        install("com.example.platform", "Platform", 9_999, 0);
        install("com.example.shared", "Shared with Tracewall", 15_000, 0);

        List<InstalledAppCatalog.Entry> entries = InstalledAppCatalog.scan(app, 1000);

        assertEquals(3, entries.size());
        assertEntry(entries.get(0), "com.example.alpha", "alpha", 12_002);
        assertEntry(entries.get(1), "com.example.updated", "Beta", 12_003);
        assertEntry(entries.get(2), "com.example.zebra", "Zebra", 12_001);
        assertThrows(UnsupportedOperationException.class,
                () -> entries.add(entries.get(0)));
    }

    @Test public void capIsAppliedAfterDeterministicNameSortingAndBrokenLabelsFallBack() {
        install("com.example.zed", "Zed", 12_010, 0);
        install("com.example.fallback", "   ", 12_011, 0);
        install("com.example.alpha", "Alpha", 12_012, 0);

        List<InstalledAppCatalog.Entry> firstTwo = InstalledAppCatalog.scan(app, 2);

        assertEquals(2, firstTwo.size());
        assertEquals("Alpha", firstTwo.get(0).label);
        assertEquals("com.example.fallback", firstTwo.get(1).label);
        assertFalse(Character.isHighSurrogate(firstTwo.get(1).label.charAt(
                firstTwo.get(1).label.length() - 1)));
    }

    private void install(String packageName, String label, int uid, int flags) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = uid;
        info.applicationInfo.flags = flags;
        info.applicationInfo.nonLocalizedLabel = label;
        packages.installPackage(info);
    }

    private static void assertEntry(InstalledAppCatalog.Entry entry,
            String packageName, String label, int uid) {
        assertEquals(packageName, entry.packageName);
        assertEquals(label, entry.label);
        assertEquals(uid, entry.uid);
    }
}
