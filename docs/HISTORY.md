# What was tried (short history)

Goal: free Android app that shows Google Maps turn-by-turn on a Royal Enfield Tripper Pod fitted to a Himalayan 411. Written by a rider who does not code, with Claude writing the code; every claim below was tested on the real Pod. The detailed session notes are in DEVELOPMENT_LOG.md.

1. **Protocol.** Used the WazeTripper protocol notes. BLE scan for `RE_DISP`, GATT server + client, PIN handshake, ping every 2 s. Worked on the first try for pairing; reconnects use the "already paired" handshake.
2. **Reading Google Maps.** NotificationListenerService on Maps' ongoing navigation notification. Formats differ: vivo/Android 15 and Samsung Android 16 (title = distance, text = road, subText = "N min · dist · ETA"); Samsung Android 17 uses ProgressStyle with `android.ongoingActivityNoti.*` extras and "Arrive HH:MM". Parser handles all three, English only.
3. **Arrows.** Maps often gives the turn only as a picture. A classifier compares the 32 x 32 picture to stored samples (IoU 0.80, small shift allowed; left/right are mirrors). Unrecognised arrows are recorded in the log. A bug showed a wrong old icon for unknown arrows; now it shows distance only.
4. **Compass.** Built, drawn distorted on the Pod (first-generation Pod limitation reported by the Super TripperPod developer). Dropped.
5. **Colours.** Byte 6 explored with a test button and filmed: 00 grey+red head, 10/20/30 gradual red, 40 flashing, 50 yellow, +1 night. App now picks the look by distance (like the official app).
6. **Pod watchdog.** Test screens vanished after 1-2 s and the link dropped. Cause: the Pod closes the link about 3-5 s after the last screen packet; pings do not count. Fix: re-send the screen every 1.9 s.
7. **Call alert.** The `40 05` call packet shows nothing; the helmet picture is just the Pod's standby picture. All 256 icon codes were cycled and filmed: there is no call icon (codes 4B-FF are blank). Decision: show the ferry icon as the call alert.
8. **Disconnects during rides.** Suspected the removed location foreground service. Re-added an optional GPS keep-alive; not proven.
9. **Icon map.** All Pod icons 00-4A documented in ICON_MAP.md. Matching Maps pictures will be added when captured.
10. **Test tooling.** `tools/FrameCheck.java` checks packets, CRC, parser and arrow matching on a PC. Mock-location (fake GPS) routes capture Maps arrows without riding.
