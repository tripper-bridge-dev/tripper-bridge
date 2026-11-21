package com.tripperbridge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of the Google Maps navigation notification into a Pod icon, distance and time left.
 * Pure Java. Works on English text only, so keep Google Maps in English.
 */
public final class MapsParser {
    private MapsParser() {}

    public static final class Info {
        public int icon = -1;
        public int distM = -1;
        public int timeMin = -1;
        public boolean recalculating = false;
        public String maneuverText = "";

        @Override
        public String toString() {
            return "icon=" + (icon < 0 ? "?" : String.format("%02X", icon))
                    + " dist=" + distM + "m time=" + timeMin + "min"
                    + (recalculating ? " recalculating" : "")
                    + " text=\"" + maneuverText + "\"";
        }
    }

    private static final Pattern DIST = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|miles?|mi|ft|feet|yd|m)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS = Pattern.compile(
            "(\\d+)\\s*h(?:ou)?r?s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINS = Pattern.compile(
            "(\\d+)\\s*min(?:ute)?s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXIT_NUM = Pattern.compile("(\\d+)(?:st|nd|rd|th)\\s+exit");
    private static final Pattern LEFT = Pattern.compile("\\bleft\\b");
    private static final Pattern RIGHT = Pattern.compile("\\bright\\b");
    private static final String[] ORD = {
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth"
    };

    /**
     * primary = title/text/big text/view texts of the notification, sub = the sub-text line (may be null).
     * rbSet: 0 = left-hand-traffic roundabout icons (India), 1 = right-hand-traffic set.
     */
    public static Info parse(List<String> primary, String sub, int rbSet) {
        return parse(primary, sub, rbSet, -1);
    }

    /** nowMinOfDay = minutes since midnight on the phone clock (or -1): used when only an arrival time is shown. */
    public static Info parse(List<String> primary, String sub, int rbSet, int nowMinOfDay) {
        Info info = new Info();
        for (String s : primary) {
            if (s == null) continue;
            String t = s.toLowerCase(Locale.ROOT);
            if (t.contains("rerout") || t.contains("re-rout") || t.contains("recalculat")) {
                info.recalculating = true;
            }
            if (info.icon < 0) {
                int ic = iconFor(t, rbSet);
                if (ic >= 0) {
                    info.icon = ic;
                    info.maneuverText = s;
                }
            }
        }
        for (String s : primary) {
            int d = firstDistance(s);
            if (d >= 0) {
                info.distM = d;
                break;
            }
        }
        if (sub != null) {
            info.timeMin = timeFrom(sub);
        }
        if (info.timeMin < 0) {
            for (String s : primary) {
                int t = timeFrom(s);
                if (t >= 0) {
                    info.timeMin = t;
                    break;
                }
            }
        }
        if (info.timeMin < 0 && nowMinOfDay >= 0) {
            int t = minutesUntilClock(sub, nowMinOfDay);
            if (t < 0) {
                for (String s : primary) {
                    t = minutesUntilClock(s, nowMinOfDay);
                    if (t >= 0) break;
                }
            }
            info.timeMin = t;
        }
        return info;
    }

    private static final Pattern CLOCK = Pattern.compile(
            "(?:arrive\\s+|^|\\s)(\\d{1,2}):(\\d{2})\\s*([ap]\\.?m\\.?)?", Pattern.CASE_INSENSITIVE);

    /** "Arrive 12:07", "6:57 am ETA" or "19:37 ETA" -> minutes from now (0..1439), or -1 if no clock time. */
    static int minutesUntilClock(String s, int nowMinOfDay) {
        if (s == null) return -1;
        Matcher m = CLOCK.matcher(s);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        if (mi > 59 || h > 23) return -1;
        String ap = m.group(3);
        if (ap != null) {
            boolean pm = ap.toLowerCase(Locale.ROOT).startsWith("p");
            if (h < 1 || h > 12) return -1;
            h = (h % 12) + (pm ? 12 : 0);
        }
        return ((h * 60 + mi) - nowMinOfDay + 1440) % 1440;
    }

    static int firstDistance(String s) {
        if (s == null) return -1;
        Matcher m = DIST.matcher(s);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = m.group(2).toLowerCase(Locale.ROOT);
        double f;
        if (u.equals("km")) f = 1000.0;
        else if (u.startsWith("mi")) f = 1609.344;
        else if (u.equals("ft") || u.equals("feet")) f = 0.3048;
        else if (u.equals("yd")) f = 0.9144;
        else f = 1.0;
        return (int) Math.round(v * f);
    }

    static int timeFrom(String s) {
        if (s == null) return -1;
        int h = -1;
        int m = -1;
        Matcher mh = HOURS.matcher(s);
        if (mh.find()) h = Integer.parseInt(mh.group(1));
        Matcher mm = MINS.matcher(s);
        if (mm.find()) m = Integer.parseInt(mm.group(1));
        if (h < 0 && m < 0) return -1;
        return Math.max(h, 0) * 60 + Math.max(m, 0);
    }

    static int exitNumber(String t) {
        Matcher m = EXIT_NUM.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1) return 0;
            return Math.min(n, 9);
        }
        for (int i = 0; i < ORD.length; i++) {
            if (t.contains(ORD[i] + " exit")) return i + 1;
        }
        return 0;
    }

    /** t must be lower-case. Returns a Pod icon code or -1 if the text is not a recognised maneuver. */
    static int iconFor(String t, int rbSet) {
        boolean l = LEFT.matcher(t).find();
        boolean r = RIGHT.matcher(t).find();
        boolean onlyL = l && !r;
        boolean onlyR = r && !l;

        if (t.contains("roundabout") || t.contains("traffic circle") || t.contains("rotary")) {
            int n = exitNumber(t);
            if (rbSet == 0) return n < 1 ? 0x0A : 0x0B + (n - 1);
            return n < 1 ? 0x31 : 0x32 + (n - 1);
        }
        if (t.contains("u-turn") || t.contains("u turn") || t.contains("uturn")) {
            // Left-hand traffic (India): a plain U-turn is made to the right = clockwise icon.
            return onlyL ? 0x3D : 0x1A;
        }
        if (t.contains("destination") || t.contains("arrive") || t.contains("arriving")) {
            if (onlyL) return 0x01;
            if (onlyR) return 0x02;
            return 0x00;
        }
        if (t.contains("ferry")) return 0x3E;
        if (t.contains("fork")) {
            if (onlyR) return 0x05;
            if (onlyL) return 0x06;
            return 0x09;
        }
        if (t.contains("merge")) {
            if (onlyR) return 0x03;
            if (onlyL) return 0x04;
            return 0x1B;
        }
        if (t.contains("exit") || t.contains("ramp")) {
            if (onlyL) return 0x2E;
            if (onlyR) return 0x2D;
            return 0x09;
        }
        if (t.contains("slight") || t.contains("bear")) {
            if (onlyL) return 0x18;
            if (onlyR) return 0x19;
        }
        if (t.contains("sharp")) {
            if (onlyL) return 0x16;
            if (onlyR) return 0x17;
        }
        if (t.contains("keep")) {
            if (onlyL) return 0x28;
            if (onlyR) return 0x27;
        }
        if (t.contains("turn")) {
            if (onlyL) return 0x14;
            if (onlyR) return 0x15;
        }
        if (t.contains("continue") || t.contains("straight") || t.startsWith("head")) {
            return 0x09;
        }
        if (t.startsWith("left")) return 0x14;
        if (t.startsWith("right")) return 0x15;
        return -1;
    }
}
