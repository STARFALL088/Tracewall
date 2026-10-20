package tracewall.child.log;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * The on-device history database.
 *
 * No fallbackToDestructiveMigration: this ships at version 1 with a stated
 * schema, and silently dropping a parent's history on an upgrade is exactly the
 * kind of thing that should fail loudly during development instead.
 */
@Database(entities = {QueryRecord.class}, version = 1, exportSchema = false)
public abstract class TracewallDb extends RoomDatabase {

    public abstract QueryDao queries();

    private static volatile TracewallDb instance;

    public static TracewallDb get(Context ctx) {
        TracewallDb local = instance;
        if (local == null) {
            synchronized (TracewallDb.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(ctx.getApplicationContext(),
                                    TracewallDb.class, "tracewall.db")
                            // One writer thread (HistoryWriter) and one reader
                            // (the provider); WAL keeps the read off the write.
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
