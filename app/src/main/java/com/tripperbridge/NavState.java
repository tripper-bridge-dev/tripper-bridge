package com.tripperbridge;

/** Shared state between the notification reader and the Bluetooth service (same process). */
public final class NavState {
    private NavState() {}

    /** Latest parsed Google Maps navigation data, or null when Google Maps is not navigating. */
    public static volatile MapsParser.Info info = null;
    public static volatile String lastRaw = "(nothing yet - start navigation in Google Maps)";
    public static volatile long lastUpdate = 0;
    /** Only the arrow pictures the reader did not recognise (for the "Copy arrow pictures" button). */
    public static volatile String arrowText = "(none yet)";
}
