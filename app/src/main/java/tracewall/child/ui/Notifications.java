package tracewall.child.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import tracewall.child.MainActivity;
import tracewall.child.R;

/**
 * The one notification this app posts: "protection is not running".
 *
 * A reboot cannot be repaired silently. Android 8+ forbids starting a plain
 * background Service from a boot receiver, and a VpnService started that way is
 * exactly what gets killed with "app is in background". The supported automatic
 * path is always-on VPN, where the system itself restarts the tunnel - so when
 * that has not happened, the honest thing is to tell the user with one tap to
 * the app rather than pretend a background start will work.
 */
public final class Notifications {

    public static final String CHANNEL_ID = "tracewall.status";
    public static final int ID_PROTECTION_OFF = 1;

    private Notifications() { }

    public static void showNewApp(Context ctx, String label, String packageName) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(ctx);
        PendingIntent tap = PendingIntent.getActivity(ctx, 2, new Intent(ctx, AppBlockActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        NotificationManagerCompat.from(ctx).notify(2, builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(ctx.getString(R.string.new_app_title))
            .setContentText(ctx.getString(R.string.new_app_body, label, packageName))
            .setContentIntent(tap).setAutoCancel(true).build());
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                ctx.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(ctx.getString(R.string.notif_channel_desc));
        nm.createNotificationChannel(channel);
    }

    public static void showProtectionOff(Context ctx, boolean wasEnabled) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;                 // user declined; nothing to do silently
        }
        ensureChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        Notification n = builder
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(ctx.getString(
                        wasEnabled ? R.string.notif_text_restart : R.string.notif_text_off))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();

        NotificationManagerCompat.from(ctx).notify(ID_PROTECTION_OFF, n);
    }
}
