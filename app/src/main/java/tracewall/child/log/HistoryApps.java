package tracewall.child.log;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.TreeSet;

import tracewall.child.R;
import tracewall.data.HistoryFilter;
import tracewall.data.TextFilter;

/** PackageManager work stays off the UI thread. A name resolves to a frozen set of UIDs. */
public final class HistoryApps {
    public static final class TooManyMatchesException extends IllegalArgumentException { }
    private final Context context;
    private final AppLabels labels;

    public HistoryApps(Context context) {
        this.context = context.getApplicationContext();
        labels = new AppLabels(this.context.getPackageManager());
    }

    public String label(int uid) {
        HistoryReader.checkCancelled();
        return uid < 0 ? context.getString(R.string.history_unknown_app) : labels.label(uid);
    }

    public int[] resolve(String search) {
        String text = search == null ? "" : search.trim();
        if (text.isEmpty()) return null;
        Integer exact = HistoryFilter.exactUid(text);
        if (exact != null) return new int[] { exact };
        PackageManager pm = context.getPackageManager();
        TreeSet<Integer> matches = new TreeSet<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            HistoryReader.checkCancelled();
            if (TextFilter.matches(text, info.packageName, String.valueOf(info.loadLabel(pm)))) {
                matches.add(info.uid);
                if (matches.size() > HistoryFilter.MAX_UIDS) throw new TooManyMatchesException();
            }
        }
        int[] uids = new int[matches.size()];
        int index = 0;
        for (int uid : matches) uids[index++] = uid;
        return uids;
    }
}
