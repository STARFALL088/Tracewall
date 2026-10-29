package tracewall.child.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tracewall.child.R;
import tracewall.security.PinStore;

/** Shared PIN prompts for all local changes that relax protection. */
public final class PinGate {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static PinStore instance;

    private static synchronized PinStore store(Context context) {
        if (instance == null) {
            SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("tracewall.pin", Context.MODE_PRIVATE);
            instance = new PinStore(new PinStore.Storage() {
                public String get(String key) { return prefs.getString(key, null); }
                public void put(String key, String value) {
                    prefs.edit().putString(key, value).commit();
                }
            });
        }
        return instance;
    }

    public static void ensureSet(AppCompatActivity activity) {
        if (!store(activity).isSet()) prompt(activity, true, () -> {});
    }

    public static void require(AppCompatActivity activity, Runnable allowed) {
        // If setup has not finished, setting a PIN must not authorize an action.
        if (!store(activity).isSet()) {
            prompt(activity, true, () -> {});
            return;
        }
        prompt(activity, false, allowed);
    }

    private static void prompt(AppCompatActivity activity, boolean setup, Runnable allowed) {
        PinStore p = store(activity);
        long wait = p.lockedFor(System.currentTimeMillis());
        if (!setup && wait > 0) {
            Toast.makeText(activity, activity.getString(R.string.pin_locked,
                (wait + 999) / 1000), Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.LinearLayout fields = new android.widget.LinearLayout(activity);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.setPadding(32, 8, 32, 8);
        EditText input = field(activity, R.string.pin_hint);
        fields.addView(input);
        EditText confirmation = field(activity, R.string.pin_confirm);
        if (setup) fields.addView(confirmation);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(setup ? R.string.pin_setup : R.string.pin_required)
            .setMessage(R.string.pin_body)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                if (setup) activity.finish();
            })
            .setCancelable(!setup)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String pin = input.getText().toString();
                if (setup && (!PinStore.validPin(pin)
                        || !pin.equals(confirmation.getText().toString()))) {
                    input.setError(activity.getString(R.string.pin_invalid));
                    return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                IO.execute(() -> {
                    boolean ok;
                    if (setup) { p.set(pin); ok = true; }
                    else ok = p.verify(pin, System.currentTimeMillis());
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        dialog.dismiss();
                        input.setText("");
                        confirmation.setText("");
                        if (ok) allowed.run();
                        else {
                            Toast.makeText(activity, R.string.pin_wrong, Toast.LENGTH_SHORT).show();
                            prompt(activity, false, allowed);
                        }
                    });
                });
            }));
        dialog.show();
    }

    private static EditText field(Context context, int hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(6) });
        return input;
    }

    private PinGate() {}
}
