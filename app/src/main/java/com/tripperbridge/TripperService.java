package com.tripperbridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the Bluetooth link to the Tripper Pod alive and decides what the Pod should show:
 * test screen > call icon > Google Maps turn (distance only if the arrow is unknown) > the Pod's own clock.
 * All real work runs on the main thread; Bluetooth callbacks are bounced onto it.
 */
@SuppressLint("MissingPermission")
public class TripperService extends Service {

    public static final String ACTION_PIN = "com.tripperbridge.PIN";
    public static final String ACTION_FORGET = "com.tripperbridge.FORGET";
    public static final String EXTRA_PIN = "pin";

    private static final UUID SVC = UUID.fromString(TripperProtocol.SERVICE_UUID);
    private static final UUID CHR = UUID.fromString(TripperProtocol.CHAR_UUID);
    private static final String CHANNEL = "tripper_bridge";

    private static final int S_SCAN = 0;
    private static final int S_CONNECTING = 1;
    private static final int S_HANDSHAKE = 2;
    private static final int S_WAIT_PIN = 3;
    private static final int S_READY = 4;

    public static volatile TripperService instance = null;
    public static volatile String status = "Stopped";

    private Handler main;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    private BluetoothManager btm;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private volatile BluetoothGattServer server;
    private BluetoothGattCharacteristic writeChar;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

    private boolean running = false;
    private boolean scanning = false;
    private int state = S_SCAN;
    private int gen = 0;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private long lastWrite = 0;
    private long lastScreenSend = 0;
    private byte[] screen = null;

    private boolean callActive = false;
    private long callTestUntil = 0;
    private long explorerStart = 0;
    private long explorerUntil = 0;
    private int explorerLast = -1;
    /** The Pod has no call icon; the ferry picture is used instead (no ferries where the rider lives). */
    private static final int CALL_ICON = 0x3E;
    private long cycleStart = 0;
    private long cycleUntil = 0;
    private int cycleBase = 0;
    private int cycleLast = -1;
    private int screensSent = 0;
    private long lastStat = 0;
    private LocationManager locMgr;
    private LocationListener locListener;
    private byte[] testPacket = null;
    private long testUntil = 0;
    private long navStartedAt = 0;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        main = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("tb", MODE_PRIVATE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startAll();
        }
        String a = intent == null ? null : intent.getAction();
        if (ACTION_PIN.equals(a)) {
            handlePin(intent.getStringExtra(EXTRA_PIN));
        } else if (ACTION_FORGET.equals(a)) {
            prefs.edit().putBoolean("paired", false).remove("pairedAddr").apply();
            lastIgnored = "";
            Logger.log("Pairing forgotten. The Pod will show a PIN on the next connect.");
            if (gatt != null) {
                gatt.disconnect();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (locMgr != null && locListener != null) locMgr.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        stopScan();
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
        try {
            unregisterReceiver(callReceiver);
        } catch (Exception ignored) {
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        status = "Stopped";
        Logger.log("Bridge stopped");
        instance = null;
        super.onDestroy();
    }

    private boolean goForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Tripper Bridge", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Tripper Bridge is running")
                    .setContentText("Sending directions to the Tripper Pod")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                boolean wantLoc = prefs.getBoolean("locFgs", true)
                        && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                try {
                    startForeground(1, n, wantLoc ? (type | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) : type);
                } catch (SecurityException se) {
                    Logger.log("Location service type refused, using plain type: " + se.getMessage());
                    startForeground(1, n, type);
                }
            } else {
                startForeground(1, n);
            }
            return true;
        } catch (Exception e) {
            Logger.log("Cannot start the background service: " + e);
            status = "Could not start: " + e.getMessage();
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void startGps() {
        if (!prefs.getBoolean("locFgs", true)) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locMgr = (LocationManager) getSystemService(LOCATION_SERVICE);
            locListener = new LocationListener() {
                @Override public void onLocationChanged(android.location.Location l) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
                @Override public void onStatusChanged(String p, int st, Bundle b) { }
            };
            locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, locListener);
            Logger.log("Location keep-alive: on");
        } catch (Exception e) {
            Logger.log("Location keep-alive failed: " + e);
        }
    }

    private void startAll() {
        running = true;
        Logger.log("Bridge starting");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tripperbridge:svc");
        wakeLock.acquire();

        btm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = btm == null ? null : btm.getAdapter();
        openServer();
        startGps();
        registerCalls();
        main.postDelayed(ticker, 500);
        state = S_SCAN;
        startScan();
    }

    // ---------------------------------------------------------------- helpers

    private void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            Logger.log("Status: " + s);
        }
    }

    private void later(long ms, final Runnable r) {
        final int my = gen;
        main.postDelayed(() -> {
            if (my == gen && running) {
                r.run();
            }
        }, ms);
    }

    private boolean isNight() {
        int mode = prefs.getInt("night", 0);
        if (mode == 1) return false;
        if (mode == 2) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6;
    }

    // ---------------------------------------------------------------- scanning and connecting

    private void startScan() {
        if (!running || state != S_SCAN || scanning) return;
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Bluetooth is off - turn it on");
            main.postDelayed(this::startScan, 3000);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            main.postDelayed(this::startScan, 3000);
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName(TripperProtocol.POD_NAME).build());
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build());
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(filters, st, scanCallback);
            scanning = true;
            setStatus("Looking for the Pod (turn the ignition on)");
        } catch (Exception e) {
            Logger.log("Scan failed to start: " + e);
            main.postDelayed(this::startScan, 3000);
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice d = result.getDevice();
            main.post(() -> found(d));
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                Logger.log("Scan failed, error " + errorCode);
                scanning = false;
                main.postDelayed(TripperService.this::startScan, 3000);
            });
        }
    };

    private String podAddr = "";
    private String lastIgnored = "";

    private void found(BluetoothDevice d) {
        if (!running || state != S_SCAN) return;
        String known = prefs.getString("pairedAddr", "");
        if (prefs.getBoolean("paired", false) && !known.isEmpty() && !known.equals(d.getAddress())) {
            // Another Pod nearby (e.g. a friend's bike): ignore it. "Forget pairing" clears this.
            if (!d.getAddress().equals(lastIgnored)) {
                lastIgnored = d.getAddress();
                Logger.log("Ignoring another Pod " + d.getAddress() + " (paired with " + known + ")");
            }
            return;
        }
        podAddr = d.getAddress();
        stopScan();
        state = S_CONNECTING;
        Logger.log("Found Pod " + d.getAddress());
        setStatus("Connecting to the Pod...");
        gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            main.post(() -> onConn(g, st, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            main.post(() -> onServices(g, st));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            main.post(() -> onWritten(st));
        }
    };

    private void onConn(BluetoothGatt g, int st, int newState) {
        if (!running) return;
        if (g != gatt) {
            try {
                g.close();
            } catch (Exception ignored) {
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Logger.log("Connected; discovering services");
            g.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Logger.log("Disconnected (status " + st + ")");
            teardown();
        }
    }

    private void teardown() {
        gen++;
        main.removeCallbacks(writeTimeout);
        queue.clear();
        writing = false;
        screen = null;
        writeChar = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        state = S_SCAN;
        setStatus("Pod disconnected - looking again");
        main.postDelayed(this::startScan, 1000);
    }

    private void onServices(BluetoothGatt g, int st) {
        if (!running || g != gatt) return;
        if (st != BluetoothGatt.GATT_SUCCESS) {
            Logger.log("Service discovery failed, status " + st);
            g.disconnect();
            return;
        }
        BluetoothGattService s = g.getService(SVC);
        writeChar = s == null ? null : s.getCharacteristic(CHR);
        if (writeChar == null) {
            Logger.log("The Pod's service was not found - is this really the Pod?");
            g.disconnect();
            return;
        }
        boolean noResp = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        writeType = noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        Logger.log("Pod service found, write type " + (noResp ? "no-response" : "default"));
        later(200, this::beginHandshake);
    }

    // ---------------------------------------------------------------- handshake

    private void beginHandshake() {
        state = S_HANDSHAKE;
        if (prefs.getBoolean("paired", false) && prefs.getString("pairedAddr", "").isEmpty()) {
            prefs.edit().putString("pairedAddr", podAddr).apply(); // older install: remember this Pod
        }
        if (prefs.getBoolean("paired", false)) {
            Logger.log("Handshake: resume (already paired)");
            enqueue(TripperProtocol.handshakeResume());
            later(200, this::sendTime);
            later(350, () -> {
                enqueue(TripperProtocol.ping());
                enqueue(TripperProtocol.ping());
            });
            later(650, () -> {
                state = S_READY;
                setStatus("Connected to the Pod");
            });
        } else {
            Logger.log("Handshake: asking the Pod to show its PIN");
            enqueue(TripperProtocol.handshakeShowPin());
            state = S_WAIT_PIN;
            setStatus("Type the PIN shown on the Pod, then tap Send PIN");
        }
    }

    private void sendTime() {
        Calendar c = Calendar.getInstance();
        enqueue(TripperProtocol.setTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true));
    }

    private void handlePin(String pin) {
        if (state != S_WAIT_PIN) {
            Logger.log("PIN ignored: the Pod is not asking for one right now");
            return;
        }
        if (pin == null || pin.isEmpty()) {
            Logger.log("PIN is empty");
            return;
        }
        Logger.log("Sending PIN");
        state = S_HANDSHAKE;
        enqueue(TripperProtocol.pin(pin));
        later(300, () -> {
            sendTime();
            enqueue(TripperProtocol.ping());
            enqueue(TripperProtocol.ping());
        });
        later(1500, () -> {
            prefs.edit().putBoolean("paired", true).putString("pairedAddr", podAddr).apply();
            state = S_READY;
            setStatus("Connected to the Pod (paired)");
        });
    }

    // ---------------------------------------------------------------- writing

    private final Runnable writeTimeout = () -> {
        Logger.log("Write timeout (no confirmation from the phone's Bluetooth)");
        writing = false;
        pump();
    };

    private void enqueue(byte[] p) {
        queue.add(p);
        while (queue.size() > 6) {
            queue.poll();
        }
        pump();
    }

    private void pump() {
        if (writing || gatt == null || writeChar == null) return;
        byte[] p = queue.poll();
        if (p == null) return;
        writing = true;
        lastWrite = SystemClock.elapsedRealtime();
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(writeChar, p, writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                writeChar.setWriteType(writeType);
                writeChar.setValue(p);
                ok = gatt.writeCharacteristic(writeChar);
            }
        } catch (Exception e) {
            Logger.log("Write error: " + e);
            ok = false;
        }
        if (ok) {
            main.postDelayed(writeTimeout, 400);
        } else {
            writing = false;
            Logger.log("Write refused: " + TripperProtocol.hex(p));
        }
    }

    private void onWritten(int st) {
        main.removeCallbacks(writeTimeout);
        writing = false;
        if (st != BluetoothGatt.GATT_SUCCESS) {
            Logger.log("Write status " + st);
        }
        pump();
    }

    // ---------------------------------------------------------------- what to show

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                tick();
            } catch (Throwable t) {
                Logger.log("Tick error: " + t);
            }
            main.postDelayed(this, 500);
        }
    };

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (state == S_HANDSHAKE || state == S_WAIT_PIN) {
            if (now - lastWrite >= 2000) {
                enqueue(TripperProtocol.ping());
            }
            return;
        }
        if (state != S_READY) return;

        if (callActive || now < callTestUntil) {
            // Call alert: the Pod has no call icon, so the ferry picture (0x3E) stands in for it.
            // It is a normal screen, so it also keeps the Pod link alive.
            byte[] cw = TripperProtocol.navigate(CALL_ICON, 0, 0xFF, isNight(), bottomNone());
            if (!Arrays.equals(cw, screen) || now - lastScreenSend >= 1900) {
                if (screen == null || screen[2] != cw[2]) {
                    Logger.log("Call alert -> ferry icon on the Pod");
                }
                screen = cw;
                lastScreenSend = now;
                screensSent++;
                enqueue(cw);
            }
            return;
        }

        if (now - lastStat >= 60000) {
            if (lastStat != 0) {
                Logger.log("Stats: " + screensSent + " screens sent in the last minute");
            }
            screensSent = 0;
            lastStat = now;
        }

        byte[] want = desiredScreen(now);
        if (want != null) {
            if (!Arrays.equals(want, screen) || now - lastScreenSend >= 1900) {
                if (screen == null || want[1] != screen[1] || want[2] != screen[2]) {
                    Logger.log("Pod screen -> " + TripperProtocol.hex(want));
                }
                screen = want;
                lastScreenSend = now;
                screensSent++;
                enqueue(want);
            }
        } else {
            screen = null;
            if (now - lastWrite >= 2000) {
                enqueue(TripperProtocol.ping());
            }
        }
    }

    private byte[] desiredScreen(long now) {
        boolean night = isNight();
        if (now < testUntil && testPacket != null) {
            return testPacket;
        }
        if (cycleLast != -1 && now >= cycleUntil) {
            Logger.log("Icon cycle: finished");
            cycleLast = -1;
        }
        if (now < cycleUntil) {
            long t = now - cycleStart;
            if (t < 3000) {
                return TripperProtocol.navigateRaw6(0xFF, 200, 0x00, bottomNone());
            }
            int code = cycleBase + (int) ((t - 3000) / 2400);
            if (code != cycleLast) {
                cycleLast = code;
                Logger.log(String.format("Icon cycle: code %02X", code));
            }
            return TripperProtocol.navigateRaw6(code, 200, 0x00, bottomNone());
        }
        MapsParser.Info i = NavState.info;
        if (i != null) {
            if (navStartedAt == 0) {
                navStartedAt = now;
            }
            if (now - navStartedAt < 2000) {
                return TripperProtocol.routeStarted(night);
            }
            if (i.recalculating) {
                return TripperProtocol.recalculating(night);
            }
            int[] bottom = TripperProtocol.bottomTimeRemaining(i.timeMin);
            // Unknown arrow: no icon (0xFF), never an old one. Unknown distance = -1 (far look, 0 M).
            return TripperProtocol.navigate(i.icon >= 0 ? i.icon : 0xFF, i.distM, 0xFF, night, bottom);
        } else {
            navStartedAt = 0;
        }
        if (now < explorerUntil) {
            int step = (int) ((now - explorerStart) / 3000);
            int b6 = (step % 6) * 0x10 + (step >= 6 ? 1 : 0);
            if (b6 != explorerLast) {
                explorerLast = b6;
                Logger.log(String.format("Explorer: byte [6] = %02X", b6));
            }
            return TripperProtocol.navigateRaw6(0x14, 200, b6, bottomNone());
        }
        return null;
    }

    private static int[] bottomNone() {
        return new int[] {0xFF, 0xFF, 0xFF};
    }

    /** Bench test from the app: 0 = left turn 200 m, 1 = right turn 1.2 km, 2 = roundabout 2nd exit, 3 = call alert (ferry icon), 6 = byte [6] explorer, 7 = distance only. */
    public static void test(int kind) {
        TripperService s = instance;
        if (s == null) {
            Logger.log("Test ignored: the bridge is not running");
            return;
        }
        s.main.post(() -> s.runTest(kind));
    }

    private void runTest(int kind) {
        long now = SystemClock.elapsedRealtime();
        boolean night = isNight();
        int[] none = {0xFF, 0xFF, 0xFF};
        if (kind == 0) {
            testPacket = TripperProtocol.navigate(0x14, 200, 0xFF, night, none);
        } else if (kind == 1) {
            testPacket = TripperProtocol.navigate(0x15, 1200, 0xFF, night, TripperProtocol.bottomTimeRemaining(65));
        } else if (kind == 2) {
            int rb = prefs.getInt("rbSet", 0);
            int icon = rb == 0 ? 0x0C : 0x33;
            testPacket = TripperProtocol.navigate(icon, 80, 0xFF, night, none);
        } else if (kind == 6) {
            explorerStart = now;
            explorerUntil = now + 36000;
            explorerLast = -1;
            testUntil = 0;
            Logger.log("Test: byte [6] explorer for 36 s (steps of 3 s: 00 10 20 30 40 50, then the same +1 'night')");
            return;
        } else if (kind == 8 || kind == 9) {
            cycleBase = kind == 8 ? 0x00 : 0x80;
            cycleStart = now;
            cycleUntil = now + 3000 + 128L * 2400;
            cycleLast = -2;
            testUntil = 0;
            Logger.log(String.format("Icon cycle: start, codes %02X-%02X, 2.4 s each (blank first 3 s)", cycleBase, cycleBase + 0x7F));
            return;
        } else if (kind == 7) {
            testPacket = TripperProtocol.navigate(0xFF, 150, 0xFF, night, none);
        } else {
            callTestUntil = now + 8000;
            screen = null;
            lastScreenSend = 0;
            Logger.log("Test: call alert (ferry icon) for 8 s");
            return;
        }
        testUntil = now + 8000;
        screen = null;
        lastScreenSend = 0;
        Logger.log("Test screen for 8 s: " + TripperProtocol.hex(testPacket));
    }

    // ---------------------------------------------------------------- the phone's own Bluetooth server (the Pod writes replies here)

    private void openServer() {
        if (btm == null) return;
        try {
            server = btm.openGattServer(this, serverCallback);
            if (server == null) {
                Logger.log("Could not open the phone's Bluetooth server");
                return;
            }
            BluetoothGattService svc = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(CHR,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            svc.addCharacteristic(ch);
            server.addService(svc);
        } catch (Exception e) {
            Logger.log("Server error: " + e);
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int st, int newState) {
            Logger.log("Phone server: " + device.getAddress() + " state " + newState);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            Logger.log("Pod says: " + TripperProtocol.hex(value));
            BluetoothGattServer s = server;
            if (responseNeeded && s != null) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    // ---------------------------------------------------------------- calls

    private void registerCalls() {
        try {
            IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(callReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(callReceiver, f);
            }
        } catch (Exception e) {
            Logger.log("Call detection not available: " + e);
        }
    }

    private final BroadcastReceiver callReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String st = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(st)) {
                if (!callActive) {
                    Logger.log("Incoming call -> call icon");
                }
                callActive = true;
            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)
                    || TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
                if (callActive) {
                    Logger.log("Call ended -> back to the normal screen");
                }
                callActive = false;
                screen = null;
                lastScreenSend = 0;
            }
        }
    };
}
