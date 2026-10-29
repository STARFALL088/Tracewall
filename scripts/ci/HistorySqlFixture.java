import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import tracewall.data.HistoryFilter;
import tracewall.data.HistoryQuery;

/** Exercises the real Java SQL builder against Python's SQLite in CI, without new dependencies. */
public final class HistorySqlFixture {
    public static void main(String[] a) {
        int[] uids = a[5].equals("*") ? null : a[5].equals("none") ? new int[0]
            : Arrays.stream(a[5].split(",")).mapToInt(Integer::parseInt).toArray();
        HistoryFilter filter = new HistoryFilter(a[1], HistoryFilter.Verdict.valueOf(a[2]),
            new HistoryFilter.Range(Long.parseLong(a[3]), Long.parseLong(a[4])), uids);
        long snapshot = Long.parseLong(a[6]), id = Long.parseLong(a[8]);
        HistoryQuery.Cursor cursor = id == 0 ? null : new HistoryQuery.Cursor(Long.parseLong(a[7]), id);
        HistoryQuery.Direction direction = HistoryQuery.Direction.valueOf(a[9]);
        HistoryQuery.Sql query = a[0].equals("stats") ? HistoryQuery.statistics(filter, snapshot)
            : HistoryQuery.page(filter, snapshot, cursor, direction, a[0].equals("exists") ? 1 : Integer.parseInt(a[10]));
        System.out.println(encoded(query.text));
        for (Object arg : query.arguments()) System.out.println(arg instanceof Number ? "N:" + arg : "S:" + encoded((String) arg));
    }
    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
