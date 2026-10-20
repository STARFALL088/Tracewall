package tracewall.child.log;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Read-only view onto the history table.
 *
 * Why a provider at all in a one-app project: it is the Android component that
 * separates "my data" from "data other components may read", and it is the
 * natural seam for the parent app later. Keeping it exported=false now means
 * the authority is in place and the security decision is explicit, not an
 * accident of a future refactor.
 *
 * delete() is supported for history clearing; insert and update are not, so the
 * component cannot be used to forge history.
 */
public final class QueryLogProvider extends ContentProvider {

    public static final String AUTHORITY = "tracewall.child.log";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/queries");

    private static final String MIME_DIR = "vnd.android.cursor.dir/vnd.tracewall.query";
    private static final String[] ALL = {
            "_id", "time_ms", "host", "type", "blocked", "uid",
            "policy", "latency_ms", "resolver",
    };
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 5000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) return null;

        int limit = DEFAULT_LIMIT;
        String raw = uri.getQueryParameter("limit");
        if (raw != null) {
            try {
                limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw)));
            } catch (NumberFormatException ignored) {
                // keep the default; a bad query parameter is not an error worth throwing
            }
        }

        String[] cols = projection == null || projection.length == 0 ? ALL : projection;
        List<QueryRecord> rows = TracewallDb.get(ctx).queries().recent(limit);

        MatrixCursor cursor = new MatrixCursor(cols, rows.size());
        for (QueryRecord r : rows) {
            Object[] values = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) values[i] = value(r, cols[i]);
            cursor.addRow(values);
        }
        return cursor;
    }

    private static Object value(QueryRecord r, String column) {
        switch (column) {
            case "_id": return r.id;
            case "time_ms": return r.timeMs;
            case "host": return r.host;
            case "type": return r.type;
            case "blocked": return r.blocked ? 1 : 0;
            case "uid": return r.uid;
            case "policy": return r.policy;
            case "latency_ms": return r.latencyMs;
            case "resolver": return r.resolver;
            default: return null;                 // unknown column -> null, not a crash
        }
    }

    /** Clearing history is legitimate; forging it is not. */
    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        Context ctx = getContext();
        if (ctx == null) return 0;
        return TracewallDb.get(ctx).queries().clear();
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return MIME_DIR;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("query history is read-only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("query history is read-only");
    }
}
