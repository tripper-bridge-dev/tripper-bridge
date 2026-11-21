package com.tripperbridge;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The only screen of the app. Built in code so there are no layout files to get wrong. */
public class MainActivity extends Activity {

    private SharedPreferences sp;
    private TextView statusView;
    private TextView rawView;
    private TextView logView;
    private EditText pinEdit;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String lastLog = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tb", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        root.addView(label("Tripper Bridge", 22, true));
        statusView = label("", 14, false);
        root.addView(statusView);

        root.addView(label("Setup (once)", 16, true));
        root.addView(button("1. Allow permissions", v -> askPermissions()));
        root.addView(button("2. Allow notification access (switch Tripper Bridge ON)",
                v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        root.addView(button("3. Battery: set this app to Unrestricted / not optimised",
                v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));

        root.addView(label("Run", 16, true));
        root.addView(button("START bridge", v -> {
            if (Build.VERSION.SDK_INT >= 31
                    && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Tap 1. Allow permissions first", Toast.LENGTH_LONG).show();
                return;
            }
            startSvc(new Intent(this, TripperService.class));
        }));
        root.addView(button("STOP bridge", v -> stopService(new Intent(this, TripperService.class))));

        root.addView(label("Pairing", 16, true));
        pinEdit = new EditText(this);
        pinEdit.setHint("PIN shown on the Pod");
        pinEdit.setSingleLine(true);
        root.addView(pinEdit);
        root.addView(button("Send PIN", v -> {
            Intent i = new Intent(this, TripperService.class).setAction(TripperService.ACTION_PIN)
                    .putExtra(TripperService.EXTRA_PIN, pinEdit.getText().toString().trim());
            startSvc(i);
        }));
        root.addView(button("Forget pairing (ask for a new PIN next time)", v -> {
            startSvc(new Intent(this, TripperService.class).setAction(TripperService.ACTION_FORGET));
        }));

        root.addView(label("When Google Maps is not navigating the Pod shows its own clock.", 14, false));

        root.addView(label("Pod screen brightness style:", 16, true));
        RadioGroup night = new RadioGroup(this);
        final RadioButton[] nb = new RadioButton[3];
        String[] names = {"Automatic (night 7 pm - 6 am)", "Always day", "Always night"};
        for (int i = 0; i < 3; i++) {
            nb[i] = new RadioButton(this);
            nb[i].setText(names[i]);
            nb[i].setId(View.generateViewId());
            night.addView(nb[i]);
        }
        night.check(nb[Math.max(0, Math.min(2, sp.getInt("night", 0)))].getId());
        night.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < 3; i++) {
                if (nb[i].getId() == id) sp.edit().putInt("night", i).apply();
            }
        });
        root.addView(night);

        CheckBox rb = new CheckBox(this);
        rb.setText("Roundabout icons for left-hand traffic (India). Untick if roundabout arrows look wrong.");
        rb.setChecked(sp.getInt("rbSet", 0) == 0);
        rb.setOnCheckedChangeListener((v, checked) -> sp.edit().putInt("rbSet", checked ? 0 : 1).apply());
        root.addView(rb);

        CheckBox loc = new CheckBox(this);
        loc.setText("Keep GPS on while running (may stop random disconnects). Restart the bridge after changing.");
        loc.setChecked(sp.getBoolean("locFgs", true));
        loc.setOnCheckedChangeListener((v, checked) -> sp.edit().putBoolean("locFgs", checked).apply());
        root.addView(loc);

        root.addView(label("Bench tests (bridge must be running and connected)", 16, true));
        root.addView(button("Test: turn left in 200 m", v -> TripperService.test(0)));
        root.addView(button("Test: turn right in 1.2 km, 1 h 05 left", v -> TripperService.test(1)));
        root.addView(button("Test: roundabout, 2nd exit", v -> TripperService.test(2)));
        root.addView(button("Test: call alert (ferry icon, 8 s)", v -> TripperService.test(3)));
        root.addView(button("Test: distance only, no arrow (8 s)", v -> TripperService.test(7)));
        root.addView(button("Test: icon cycle 00-7F (5 min, film the Pod)", v -> TripperService.test(8)));
        root.addView(button("Test: icon cycle 80-FF (5 min, film the Pod)", v -> TripperService.test(9)));
        root.addView(button("Test: colour explorer, byte 6 steps (36 s)", v -> TripperService.test(6)));

        root.addView(label("Troubleshooting", 16, true));
        root.addView(button("Copy log (then paste it to Claude)", v -> copyLog()));
        root.addView(button("Copy arrow pictures only (short)", v -> copyArrows()));
        root.addView(button("Clear log", v -> Logger.clear()));
        rawView = label("", 12, false);
        rawView.setTextIsSelectable(true);
        root.addView(rawView);
        logView = label("", 11, false);
        logView.setTextIsSelectable(true);
        root.addView(logView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String nav = NavState.info != null ? "Google Maps navigating" : "Google Maps not navigating";
            statusView.setText("Pod: " + TripperService.status
                    + "\nNotification access: " + (listenerEnabled() ? "ON" : "OFF - do step 2")
                    + "\n" + nav);
            rawView.setText("Last Google Maps notification:\n" + NavState.lastRaw);
            String log = Logger.dump();
            if (!log.equals(lastLog)) {
                lastLog = log;
                logView.setText(log);
            }
            h.postDelayed(this, 1000);
        }
    };

    private boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(p.toArray(new String[0]), 1);
    }

    private void startSvc(Intent i) {
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Logger.log("Start failed: " + e);
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("tripper log",
                Logger.dump() + "\nLAST GOOGLE MAPS NOTIFICATION:\n" + NavState.lastRaw
                        + "\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void copyArrows() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("tripper arrows", NavState.arrowText
                + "\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE));
        Toast.makeText(this, "Arrow pictures copied", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sizeSp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        bt.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return bt;
    }
}
