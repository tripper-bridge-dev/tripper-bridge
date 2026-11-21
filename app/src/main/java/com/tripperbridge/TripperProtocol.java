package com.tripperbridge;

/**
 * Packet builder for the Royal Enfield Tripper Pod.
 * Pure Java (no Android code) so it can be tested on a PC.
 * Byte layouts come from the WazeTripper project's docs/PROTOCOL.md (Apache-2.0, by jnsdias).
 * Every packet is 20 bytes: 18 payload bytes + 2 CRC bytes (CRC-16/CCITT-FALSE, big-endian).
 */
public final class TripperProtocol {
    private TripperProtocol() {}

    public static final String SERVICE_UUID = "01ff0100-ba5e-f4ee-5ca1-eb1e5e4b1ce0";
    public static final String CHAR_UUID = "01ff0101-ba5e-f4ee-5ca1-eb1e5e4b1ce0";
    public static final String POD_NAME = "RE_DISP";

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, MSB first, no final XOR. */
    public static int crc16(byte[] d, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (d[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }

    private static byte[] seal(byte[] p) {
        int crc = crc16(p, 18);
        p[18] = (byte) (crc >> 8);
        p[19] = (byte) crc;
        return p;
    }

    /** Keep-alive. Must be sent if 2 s pass without any write. */
    public static byte[] ping() {
        byte[] p = new byte[20];
        p[0] = 0x03;
        return seal(p);
    }

    /** Handshake for a phone that has already paired. */
    public static byte[] handshakeResume() {
        byte[] p = new byte[20];
        p[0] = 0x21;
        p[1] = 0x00;
        return seal(p);
    }

    /** Handshake for a new phone: the Pod shows a PIN on its screen. */
    public static byte[] handshakeShowPin() {
        byte[] p = new byte[20];
        p[0] = 0x21;
        p[1] = 0x01;
        return seal(p);
    }

    /** PIN typed by the user (up to 6 ASCII characters). */
    public static byte[] pin(String pin) {
        byte[] p = new byte[20];
        p[0] = 0x20;
        if (pin != null) {
            for (int i = 0; i < pin.length() && i < 6; i++) {
                p[1 + i] = (byte) pin.charAt(i);
            }
        }
        return seal(p);
    }

    /** Time sync. 24h: raw hour. 12h: hour 1-12, with 0x40 set for AM. */
    public static byte[] setTime(int hour24, int minute, boolean use24h) {
        byte[] p = new byte[20];
        p[0] = 0x50;
        if (use24h) {
            p[1] = (byte) hour24;
        } else {
            int h12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
            p[1] = (byte) (hour24 < 12 ? (h12 | 0x40) : h12);
        }
        p[2] = (byte) minute;
        return seal(p);
    }

    /** Incoming-call icon: keep-alive command 0x40 with sub-byte 0x05. Re-send while the call lasts. */
    public static byte[] call() {
        byte[] p = new byte[20];
        p[0] = 0x40;
        p[1] = 0x05;
        return seal(p);
    }

    /** Compass screen. dirCode: N=10 NE=50 E=20 SE=70 S=40 SW=80 W=30 NW=60. */
    public static byte[] compass(int dirCode, boolean night) {
        byte[] p = new byte[20];
        p[0] = 0x10;
        p[1] = 0x11;
        p[2] = 0x41;
        p[6] = (byte) (night ? 1 : 0);
        p[7] = (byte) 0xFF;
        p[11] = (byte) 0xFF;
        p[12] = (byte) 0xFF;
        p[13] = (byte) 0xFF;
        p[14] = (byte) dirCode;
        return seal(p);
    }

    /**
     * Navigation screen.
     * icon = large maneuver icon, nextIcon = small arrow (0xFF = none),
     * bottom = 3 bytes for the bottom line (0xFF,0xFF,0xFF = nothing).
     */
    /** Same as navigate() but with byte [6] given directly (for the colour/intensity experiment). */
    public static byte[] navigateRaw6(int icon, int distM, int byte6, int[] bottom) {
        byte[] p = navigate(icon, distM, 0xFF, false, bottom);
        p[6] = (byte) byte6;
        return seal(p);
    }

    public static byte[] navigate(int icon, int distM, int nextIcon, boolean night, int[] bottom) {
        int[] d = encodeDistance(distM);
        byte[] p = new byte[20];
        p[0] = 0x10;
        p[1] = 0x11;
        p[2] = (byte) icon;
        p[3] = (byte) (d[0] >> 8);
        p[4] = (byte) d[0];
        p[5] = (byte) d[1];
        p[6] = (byte) (intensity(distM) + (night ? 1 : 0));
        p[7] = (byte) nextIcon;
        p[8] = (byte) 0xFF;
        p[9] = (byte) 0xFF;
        p[10] = 0x41;
        p[11] = (byte) bottom[0];
        p[12] = (byte) bottom[1];
        p[13] = (byte) bottom[2];
        return seal(p);
    }

    /** "Route started" screen, shown about 2 s when navigation begins. */
    public static byte[] routeStarted(boolean night) {
        byte[] p = new byte[20];
        p[0] = 0x10;
        p[1] = 0x11;
        p[2] = 0x45;
        p[5] = 0x01;
        p[6] = (byte) (night ? 1 : 0);
        p[7] = (byte) 0xFF;
        p[8] = (byte) 0xFF;
        p[9] = (byte) 0xFF;
        p[10] = 0x41;
        p[11] = (byte) 0xFF;
        p[12] = (byte) 0xFF;
        p[13] = (byte) 0xFF;
        return seal(p);
    }

    /** "Recalculating" icon screen. */
    public static byte[] recalculating(boolean night) {
        byte[] p = new byte[20];
        p[0] = 0x10;
        p[1] = 0x11;
        p[2] = 0x1C;
        p[6] = (byte) (night ? 1 : 0);
        return seal(p);
    }

    /** Distance up to 999 m is sent as raw metres (unit 1); above that as metres/100 (unit 2). */
    public static int[] encodeDistance(int m) {
        if (m < 0) {
            m = 0;
        }
        if (m <= 999) {
            return new int[] {m, 1};
        }
        return new int[] {Math.min(m / 100, 65535), 2};
    }

    /** Intensity byte that depends on distance to the maneuver. */
    /** Colour/fill of the arrow by distance to the turn. Unknown (negative) = far look. */
    public static int intensity(int m) {
        if (m < 0 || m > 250) return 0x50;
        if (m > 100) return 0x00;
        if (m > 70) return 0x10;
        if (m > 45) return 0x20;
        if (m > 20) return 0x30;
        return 0x40;
    }

    /** Bottom line "time remaining": hours, minutes, 0. */
    public static int[] bottomTimeRemaining(int minutes) {
        if (minutes < 0) {
            return new int[] {0xFF, 0xFF, 0xFF};
        }
        return new int[] {Math.min(minutes / 60, 255), minutes % 60, 0};
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
