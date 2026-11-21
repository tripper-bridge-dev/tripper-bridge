# HANDOFF: Tripper Bridge (for a helper AI)

Goal: Android app (Java, no libraries) that shows Google Maps turns, a call alert on a Royal Enfield Tripper Pod (BLE name RE_DISP, Himalayan 411). User cannot code: give step-by-step help, small answers.

## State
- Version 0.8. Runs on 3 phones (vivo phone/Android 15, Samsung phone/Android 17, Samsung phone/Android 16). Pod pairing (PIN), reconnect and turn screens verified on the user's Pod. Open: random disconnects (GPS keep-alive switch added, unproven), screen waking. See docs/KNOWN_LIMITATIONS.md.
- Google Maps notification: title = distance ("160 m", sometimes blank), text = instruction or road name, subText = "7 min · 3.0 km · ETA". Turn type is often only in android.largeIcon (119x119 bitmap).
- ArrowClassifier matches the 32x32 arrow picture to real samples (IoU >= 0.80). Known: straight 0x09, right 0x15, left 0x14, merge (Y shape) 0x1B, U-turn clockwise 0x1A, destination pin 0x00, mirrored U-turn 0x3D. Merge/U-turn/pin meanings and the mirrored U-turn are INFERRED, not confirmed on the bike.
- Full Pod icon list with Maps status: docs/ICON_MAP.md. Unknown arrows show the distance only. Add new ones from real logs (Copy arrow pictures only).
- Call alert = ferry icon (0x3E) sent as a normal screen (the Pod has no call icon; verified by cycling all codes).
- Colour by distance: >250 m 0x50, 100-250 0x00, 70-100 0x10, 45-70 0x20, 20-45 0x30, <=20 0x40 (docs/PROTOCOL.md).
- Unknown distance (-1) shows "0 M" with the far-look colour.

## Files (app/src/main/java/com/tripperbridge/)
TripperProtocol (packets, CRC), MapsParser (text to icon), ArrowClassifier, NavListener (reads Maps notification), TripperService (BLE, keep-alive), MainActivity (UI), Logger, NavState. PC checks: tools/FrameCheck.java (javac the pure files + it; currently all pass).

## Rules
- Never invent packet bytes; follow TripperProtocol.java and jnsdias/wazetripper docs/PROTOCOL.md.
- Android code cannot be compiled without the SDK here; user builds in Android Studio and pastes errors.
- Add arrows only from real logs, never guess shapes (except exact mirrors).

## Next steps
1. User tests the list in docs/DEVELOPMENT_LOG.md and pastes logs; fix in batches, no coding per message.
2. Collect missing arrows; add them only from real logs.

## Working method
User records logs and pastes them. Keep notes in docs/DEVELOPMENT_LOG.md (what was tried, results, open issues). Do not code per message; batch fixes. Keep replies short (free usage limit).
