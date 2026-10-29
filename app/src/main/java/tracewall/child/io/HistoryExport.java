package tracewall.child.io;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import tracewall.child.log.AppLabels;
import tracewall.child.log.HistoryReader;
import tracewall.child.log.QueryRecord;
import tracewall.data.CsvRow;
import tracewall.data.HistoryFilter;
import tracewall.data.HistoryQuery;

/** Streams the ENTIRE selected snapshot, not the current page, with at most 250 rows in memory. */
public final class HistoryExport {
    private static final int BATCH_SIZE = 250;
    private HistoryExport() {}

    public static final class ChangedException extends IOException {
        ChangedException() { super("History was pruned or cleared during export"); }
    }

    public static final class Result {
        public final File file;
        public final long rows;
        Result(File file, long rows) { this.file = file; this.rows = rows; }
    }

    /** Called only after PinGate.require succeeds. The caller owns cancellation and sharing. */
    public static Result write(Context context, HistoryReader reader,
                               HistoryFilter filter, long snapshotId) throws IOException {
        HistoryReader.checkCancelled();
        long expected = reader.statistics(filter, snapshotId).total;
        File directory = ShareFiles.target(context, "history.csv").getParentFile();
        File file = File.createTempFile("history-", ".csv", directory);
        boolean complete = false;
        try {
            long written = 0;
            AppLabels labels = new AppLabels(context.getPackageManager());
            try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {
                output.write(CsvRow.of("id", "time_ms", "host", "type", "blocked", "uid",
                        "app", "policy", "latency_ms", "resolver"));
                HistoryQuery.Cursor after = null;
                while (true) {
                    List<QueryRecord> rows = reader.rows(filter, snapshotId, after,
                            HistoryQuery.Direction.OLDER, BATCH_SIZE);
                    if (rows.isEmpty()) break;
                    for (QueryRecord row : rows) {
                        HistoryReader.checkCancelled();
                        output.write(CsvRow.of(row.id, row.timeMs, CsvRow.spreadsheetText(row.host),
                                row.type, row.blocked, row.uid, CsvRow.spreadsheetText(labels.label(row.uid)),
                                CsvRow.spreadsheetText(row.policy), row.latencyMs, CsvRow.spreadsheetText(row.resolver)));
                        written++;
                    }
                    after = HistoryReader.cursor(rows.get(rows.size() - 1));
                }
            }
            HistoryReader.checkCancelled();
            // Avoid sharing a silently truncated file if retention/clear won a race. Inserts
            // cannot enter this snapshot, even when their timestamps are backdated.
            if (written != expected) throw new ChangedException();
            complete = true;
            return new Result(file, written);
        } finally {
            if (!complete) file.delete();
        }
    }


}
