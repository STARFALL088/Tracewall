package tracewall.child.io;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import tracewall.child.R;

/** Share only files in the narrow exports cache directory. Called on the UI thread. */
public final class ShareFiles {
    private ShareFiles() {}
    public static File target(android.content.Context context, String name) throws IOException {
        File directory = new File(context.getCacheDir(), "exports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create exports");
        return new File(directory, name);
    }
    public static void send(Activity activity, File file, String mime) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Tracewall", uri));
        try {
            if (intent.resolveActivity(activity.getPackageManager()) == null) throw new ActivityNotFoundException();
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_file)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_handler, Toast.LENGTH_LONG).show();
        }
    }
}
