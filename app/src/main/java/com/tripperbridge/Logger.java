package com.tripperbridge;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** In-memory log shown in the app, with a Copy button. Pure Java. */
public final class Logger {
    private Logger() {}

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized void log(String s) {
        LINES.addLast(FMT.format(new Date()) + "  " + s);
        while (LINES.size() > 400) {
            LINES.removeFirst();
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
