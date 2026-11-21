package com.tripperbridge;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reads the Google Maps navigation notification and hands the parsed turn data to the Bluetooth service.
 * Version 0.2: also dumps everything Google Maps posts (see "ALL GOOGLE MAPS NOTIFICATIONS" in Copy log),
 * so we can see where this phone's Google Maps keeps the turn and distance.
 */
@SuppressWarnings("deprecation")
public class NavListener extends NotificationListenerService {
    static final String MAPS = "com.google.android.apps.maps";

    private int lastIcon = -2;
    private boolean lastRecalc = false;
    private long lastUnknownLog = 0;
    private long lastDiagTime = 0;
    private String parsedText = "(nothing yet)";
    private String diagText = "";
    private final List<String> arrowHistory = new ArrayList<>();
    private String lastArrowArt = "";
    private final java.util.HashSet<String> seenArt = new java.util.HashSet<>();

    @Override
    public void onListenerConnected() {
        Logger.log("Notification access: connected (reader v0.8)");
        try {
            refreshDiag(true);
        } catch (Throwable t) {
            Logger.log("Diag error: " + t);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            Logger.log("NavListener error: " + t);
        }
        try {
            if (sbn != null && MAPS.equals(sbn.getPackageName())) {
                refreshDiag(false);
            }
        } catch (Throwable t) {
            Logger.log("Diag error: " + t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            NavState.info = null;
            lastIcon = -2;
            Logger.log("Google Maps navigation ended");
        }
    }

    // ------------------------------------------------------------------ parsing

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString().trim();
    }

    private static void add(List<String> list, CharSequence cs) {
        String s = str(cs);
        if (s != null && !s.isEmpty() && !list.contains(s)) {
            list.add(s);
        }
    }

    private static void collectExtras(List<String> list, Bundle e) {
        if (e == null) return;
        add(list, e.getCharSequence(Notification.EXTRA_TITLE));
        add(list, e.getCharSequence(Notification.EXTRA_TITLE_BIG));
        add(list, e.getCharSequence(Notification.EXTRA_TEXT));
        add(list, e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        add(list, e.getCharSequence(Notification.EXTRA_INFO_TEXT));
        // Newer phones (Android 16/17) keep the distance here instead of in the title.
        add(list, e.getCharSequence("android.ongoingActivityNoti.primaryInfo"));
        add(list, e.getCharSequence("android.ongoingActivityNoti.chipExpandedText"));
        add(list, e.getCharSequence("android.ongoingActivityNoti.nowbarPrimaryInfo"));
        add(list, e.getCharSequence("android.ongoingActivityNoti.secondaryInfo"));
        add(list, e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence c : lines) add(list, c);
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;

        Bundle e = n.extras;
        List<String> prim = new ArrayList<>();
        collectExtras(prim, e);
        add(prim, n.tickerText);
        if (n.publicVersion != null) {
            collectExtras(prim, n.publicVersion.extras);
        }
        String sub = e == null ? null : str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (prim.isEmpty()) {
            return; // a blank update between two real ones: keep the last good state
        }

        int rbSet = getSharedPreferences("tb", MODE_PRIVATE).getInt("rbSet", 0);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        MapsParser.Info info = MapsParser.parse(prim, sub, rbSet, nowMin);

        List<String> views = new ArrayList<>();
        if (info.icon < 0 || info.distM < 0) {
            collectViews(n.contentView, views);
            collectViews(n.bigContentView, views);
            collectViews(n.headsUpContentView, views);
            if (!views.isEmpty()) {
                List<String> all = new ArrayList<>(prim);
                for (String v : views) {
                    if (!all.contains(v)) all.add(v);
                }
                info = MapsParser.parse(all, sub, rbSet, nowMin);
            }
        }

        // If the text gave no turn (e.g. "towards X"), use the arrow picture.
        String art = arrowArt(n);
        ArrowClassifier.Result ar = art == null ? null : ArrowClassifier.match(art);
        if (info.icon < 0 && ar != null) {
            info.icon = ar.icon;
            info.maneuverText = "(arrow picture: " + ar.name + ")";
        }
        if (art != null && ar == null) {
            recordArrow(art, prim, info);
        }
        parsedText = "title/text: " + prim + "\nsub: " + sub + "\nview texts: " + views + "\n=> " + info;
        NavState.lastRaw = buildRaw();
        NavState.lastUpdate = System.currentTimeMillis();
        NavState.info = info;

        if (info.icon != lastIcon || info.recalculating != lastRecalc) {
            lastIcon = info.icon;
            lastRecalc = info.recalculating;
            Logger.log("Maps: " + info);
        }
        if (info.icon < 0 && !info.recalculating) {
            long now = System.currentTimeMillis();
            if (now - lastUnknownLog > 10000) {
                lastUnknownLog = now;
                Logger.log("Maps text not recognised: " + prim + " views=" + views);
            }
        }
    }

    private void collectViews(RemoteViews rv, List<String> out) {
        if (rv == null) return;
        try {
            View v = rv.apply(getApplicationContext(), new FrameLayout(this));
            walk(v, out);
        } catch (Throwable ignored) {
            // reported in the diagnostic dump
        }
    }

    private void walk(View v, List<String> out) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
        CharSequence d = v.getContentDescription();
        if (d != null) {
            String s = d.toString().trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), out);
            }
        }
    }

    // ------------------------------------------------------------------ arrow picture capture

    /** Remembers each different arrow picture Google Maps shows, drawn as text, with the text beside it. */
    private void recordArrow(String art, List<String> prim, MapsParser.Info info) {
        if (!seenArt.add(art)) return;
        lastArrowArt = art;
        String when = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String label = info.icon >= 0 ? " (text says icon " + String.format("%02X", info.icon) + ")" : "";
        arrowHistory.add("[" + when + "] text=" + prim + label + "\n" + art);
        while (arrowHistory.size() > 20) {
            arrowHistory.remove(0);
        }
    }

    /** Draws the notification's large icon into 32x32 and returns it as lines of # and . characters. */
    private String arrowArt(Notification n) {
        try {
            Icon ic = n.getLargeIcon();
            if (ic == null) return null;
            Drawable d = ic.loadDrawable(getApplicationContext());
            if (d == null) return "(large icon could not be loaded)\n";
            int size = 32;
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            int bg = bm.getPixel(0, 0);
            boolean bgTransparent = Color.alpha(bg) < 100;
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int p = bm.getPixel(x, y);
                    int dist = Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg))
                            + Math.abs(Color.blue(p) - Color.blue(bg));
                    boolean fg = Color.alpha(p) >= 100 && (bgTransparent || dist > 90);
                    sb.append(fg ? '#' : '.');
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(arrow picture failed: " + t + ")\n";
        }
    }

    private String buildRaw() {
        StringBuilder sb = new StringBuilder(parsedText);
        sb.append("\n\nARROW PICTURES SEEN (oldest first, # = arrow pixel):\n");
        if (arrowHistory.isEmpty()) {
            sb.append("(none yet)\n");
        }
        StringBuilder only = new StringBuilder();
        for (String a : arrowHistory) {
            sb.append(a).append('\n');
            only.append(a).append('\n');
        }
        NavState.arrowText = arrowHistory.isEmpty() ? "(none yet)" : only.toString();
        sb.append("\nALL GOOGLE MAPS NOTIFICATIONS RIGHT NOW:\n").append(diagText);
        return sb.toString();
    }

    // ------------------------------------------------------------------ diagnostics

    private void refreshDiag(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastDiagTime < 3000) return;
        lastDiagTime = now;
        diagText = diagAll();
        NavState.lastRaw = buildRaw();
    }

    private String diagAll() {
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (Throwable t) {
            return "getActiveNotifications failed: " + t + "\n";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (all != null) {
            for (StatusBarNotification s : all) {
                if (!MAPS.equals(s.getPackageName())) continue;
                count++;
                Notification n = s.getNotification();
                sb.append("--- Maps notification id=").append(s.getId()).append(" tag=").append(s.getTag()).append('\n');
                sb.append("channel=").append(n.getChannelId()).append(" flags=").append(n.flags)
                        .append(" category=").append(n.category).append('\n');
                sb.append("tickerText=").append(n.tickerText).append('\n');
                sb.append("publicVersion=").append(n.publicVersion != null).append('\n');
                sb.append(viewReport("contentView", n.contentView));
                sb.append(viewReport("bigContentView", n.bigContentView));
                sb.append(viewReport("headsUpContentView", n.headsUpContentView));
                sb.append("extras:\n");
                dumpExtras(sb, n.extras);
                if (n.publicVersion != null) {
                    sb.append("public extras:\n");
                    dumpExtras(sb, n.publicVersion.extras);
                }
                if (n.actions != null) {
                    for (Notification.Action a : n.actions) {
                        sb.append("action: ").append(a.title).append('\n');
                    }
                }
            }
        }
        if (count == 0) {
            sb.append("No active Google Maps notifications\n");
        }
        return sb.toString();
    }

    private String viewReport(String name, RemoteViews rv) {
        if (rv == null) return name + ": none\n";
        try {
            View v = rv.apply(getApplicationContext(), new FrameLayout(this));
            List<String> out = new ArrayList<>();
            walk(v, out);
            return name + ": " + out + "\n";
        } catch (Throwable t) {
            return name + ": could not be opened (" + t + ")\n";
        }
    }

    private void dumpExtras(StringBuilder sb, Bundle e) {
        if (e == null) {
            sb.append("  (none)\n");
            return;
        }
        for (String k : e.keySet()) {
            Object v;
            try {
                v = e.get(k);
            } catch (Throwable t) {
                v = "?";
            }
            String s;
            if (v == null) {
                s = "null";
            } else if (v instanceof CharSequence[]) {
                s = Arrays.toString((CharSequence[]) v);
            } else if (v instanceof Bitmap) {
                s = "Bitmap " + ((Bitmap) v).getWidth() + "x" + ((Bitmap) v).getHeight();
            } else {
                s = v.toString();
            }
            if (s.length() > 140) s = s.substring(0, 140) + "...";
            sb.append("  ").append(k).append(" = ").append(s);
            if (v != null) sb.append("   <").append(v.getClass().getSimpleName()).append(">");
            sb.append('\n');
        }
    }
}
