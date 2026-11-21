# Tripper Pod protocol notes (as used by this app)

Credit: byte layouts follow the WazeTripper project (jnsdias, Apache-2.0, https://github.com/jnsdias/wazetripper). Everything marked **(found here)** was measured on a first-generation Pod (Himalayan 411) with this app.

## Link
- BLE name `RE_DISP`. Service `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`, characteristic `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0`.
- The phone is the BLE central. The app also opens a GATT server, because the Pod expects one.
- Every packet is 20 bytes. Bytes 18-19 are CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) of bytes 0-17, big-endian.
- Handshake: `21 00` (already paired) or `21 01` (Pod shows a PIN), then `20 <6 ASCII PIN chars>`. After that send the clock `50 <hour> <minute>`.
- Idle keep-alive: `03 00 ...` at least every 2 s. The Pod answers `03 07 05 01 ...` about every 2 s while idle.
- **(found here) Pod watchdog:** once a screen packet has been sent, the Pod closes the link (status 19) about 3-5 s after the *last screen packet*. Ping packets and the `40 05` call packet do not renew it. So the app re-sends the current screen every ~1.9 s. A fresh connection that only sends pings keeps the Pod's own clock and stays up.

## Navigation screen
`10 11 [icon] [dist hi] [dist lo] [unit] [intensity] [next icon] FF FF 41 [bottom x3] 00 .. [CRC x2]`

| Byte | Meaning |
|---|---|
| 2 | big icon code (see ICON_MAP.md); FF = blank (distance only) |
| 3-4 | distance, big-endian |
| 5 | unit: 1 = metres (distance up to 999), 2 = distance/100 in km (for example 12 = 1.2 km) |
| 6 | intensity / colour (below); +1 = night mode (no colour) |
| 7 | small next-turn icon; app sends FF (none) |
| 11-13 | bottom line: hours, minutes, 0 (time remaining); FF FF FF = nothing |

### Byte 6 (found here, from video)
| Value | Look |
|---|---|
| 00 | grey arrow, red arrowhead |
| 10 / 20 / 30 | red fills the arrow gradually |
| 40 | flashing, almost fully red |
| 50 | yellow, filled, steady |
| +1 | night: same picture, no colour |
There is no steady full-red value among 00-50.

App mapping by distance to the turn: more than 250 m or unknown 0x50; 100-250 m 0x00; 70-100 m 0x10; 45-70 m 0x20; 20-45 m 0x30; 20 m or less 0x40.

## Other screens
- Route started: icon 0x45, unit 1.
- Recalculating: icon 0x1C.
- Compass: icon 0x41. Drawn distorted on the first-generation Pod (the Super TripperPod developer reports the same), so the app does not use it.
- Call: the `40 05` packet shows nothing visible on this Pod. The app shows the ferry icon (0x3E) as a call alert instead.
- Standby picture: a helmet (the Pod shows it by itself when nothing is sent after a screen).
