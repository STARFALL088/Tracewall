package tracewall.child.apps;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One bounded, deterministic inventory for local and consented parent app pickers. */
public final class InstalledAppCatalog {
    private static final int FIRST_APPLICATION_UID = 10_000;
    private static final int MAX_LABEL_LENGTH = 200;

    private InstalledAppCatalog() {}

    public static final class Entry {
        public final String packageName;
        public final String label;
        public final int uid;

        private Entry(String packageName, String label, int uid) {
            this.packageName = packageName;
            this.label = label;
            this.uid = uid;
        }
    }

    /**
     * Returns user-installed applications sorted by visible name then package.
     * Every package sharing Tracewall's own UID is excluded so an app rule cannot
     * cut off the filtering and parent-sync control plane itself.
     */
    public static List<Entry> scan(Context context, int limit) {
        if (context == null || limit < 0) throw new IllegalArgumentException("Invalid app scan");
        if (limit == 0) return Collections.emptyList();

        Context app = context.getApplicationContext();
        PackageManager packages = app.getPackageManager();
        int ownUid = app.getApplicationInfo().uid;
        String ownPackage = app.getPackageName();
        List<Entry> entries = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (PackageInfo info : packages.getInstalledPackages(0)) {
            ApplicationInfo application = info.applicationInfo;
            String packageName = info.packageName;
            if (application == null || packageName == null || packageName.isEmpty()
                    || packageName.equals(ownPackage)
                    || application.uid < FIRST_APPLICATION_UID || application.uid == ownUid
                    || !seenPackages.add(packageName)) continue;
            boolean systemOnly = (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (application.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (systemOnly) continue;
            entries.add(new Entry(packageName, label(packages, application, packageName), application.uid));
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.packageName));
        if (entries.size() > limit) entries = new ArrayList<>(entries.subList(0, limit));
        return Collections.unmodifiableList(entries);
    }

    private static String label(PackageManager packages, ApplicationInfo application, String packageName) {
        String label = packageName;
        try {
            CharSequence loaded = application.loadLabel(packages);
            if (loaded != null && !loaded.toString().trim().isEmpty()) label = loaded.toString().trim();
        } catch (RuntimeException ignored) {
            // A package with broken label resources remains manageable by package name.
        }
        if (label.length() <= MAX_LABEL_LENGTH) return label;
        int end = MAX_LABEL_LENGTH;
        if (Character.isHighSurrogate(label.charAt(end - 1))) end--;
        return label.substring(0, end);
    }
}
