package tracewall.data;

import java.util.ArrayList;
import java.util.List;

/** Bound SQL shared by the Room history reader, CSV streamer and sqlite regression tests. */
public final class HistoryQuery {
    private HistoryQuery() {}
    public enum Direction { OLDER, NEWER }

    public static final class Cursor {
        public final long timeMs;
        public final long id;
        public Cursor(long timeMs, long id) {
            if (id <= 0) throw new IllegalArgumentException("Invalid row ID");
            this.timeMs = timeMs;
            this.id = id;
        }
    }

    public static final class Sql {
        public final String text;
        private final Object[] arguments;
        private Sql(String text, List<Object> arguments) {
            this.text = text;
            this.arguments = arguments.toArray();
        }
        public Object[] arguments() { return arguments.clone(); }
    }

    /** NEWER returns ascending rows nearest the anchor; callers reverse for display. */
    public static Sql page(HistoryFilter filter, long snapshotId, Cursor anchor,
                           Direction direction, int limit) {
        if (direction == null || limit < 1 || limit > 501) {
            throw new IllegalArgumentException("Invalid page request");
        }
        if (anchor == null && direction != Direction.OLDER) {
            throw new IllegalArgumentException("Newer page needs an anchor");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM queries");
        where(sql, args, filter, snapshotId);
        if (anchor != null) {
            String op = direction == Direction.OLDER ? " < " : " > ";
            sql.append(" AND (timeMs").append(op).append("? OR (timeMs = ? AND id")
                    .append(op).append("?))");
            args.add(anchor.timeMs);
            args.add(anchor.timeMs);
            args.add(anchor.id);
        }
        sql.append(direction == Direction.OLDER
                ? " ORDER BY timeMs DESC, id DESC LIMIT ?"
                : " ORDER BY timeMs ASC, id ASC LIMIT ?");
        args.add(limit);
        return new Sql(sql.toString(), args);
    }

    /** Statistics describe ALL matching retained rows, never just the visible page. */
    public static Sql statistics(HistoryFilter filter, long snapshotId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN blocked != 0 THEN 1 ELSE 0 END), 0) AS blocked, "
                + "COALESCE(AVG(latencyMs), 0.0) AS averageLatencyMs FROM queries");
        where(sql, args, filter, snapshotId);
        return new Sql(sql.toString(), args);
    }

    private static void where(StringBuilder sql, List<Object> args,
                              HistoryFilter filter, long snapshotId) {
        if (filter == null || snapshotId < 0) throw new IllegalArgumentException("Invalid snapshot");
        sql.append(" WHERE id <= ? AND timeMs >= ? AND timeMs < ?");
        args.add(snapshotId);
        args.add(filter.range.startMs);
        args.add(filter.range.endMs);
        if (!filter.domain.isEmpty()) {
            sql.append(" AND host LIKE ? ESCAPE '\\'");
            args.add(filter.domainLike());
        }
        if (filter.verdict != HistoryFilter.Verdict.ALL) {
            sql.append(" AND blocked = ?");
            args.add(filter.verdict == HistoryFilter.Verdict.BLOCKED ? 1 : 0);
        }
        int[] uids = filter.uids();
        if (uids != null) {
            if (uids.length == 0) sql.append(" AND 0");
            else {
                sql.append(" AND uid IN (");
                for (int i = 0; i < uids.length; i++) {
                    if (i > 0) sql.append(',');
                    sql.append('?');
                    args.add(uids[i]);
                }
                sql.append(')');
            }
        }
    }
}
