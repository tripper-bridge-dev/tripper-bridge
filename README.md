# Tripper Bridge (version 0.8)

A free Android app that sends **turn-by-turn directions from Google Maps** and a **call alert** to the Royal Enfield **Tripper Pod** (the round Bluetooth display, BLE name `RE_DISP`). Built and tested on a Himalayan 411 with a first-generation Pod, on three Android phones (vivo Android 15, Samsung Android 16 and 17).

Google Maps runs as normal. The app reads Google Maps' navigation notification and converts it into Pod packets.

## Why this exists
The Royal Enfield Tripper Pod is a small round navigation display fitted to many Royal Enfield bikes. Its official app only works with its own navigation, and many riders prefer Google Maps for routes and live traffic. The goal of this project is simple: keep using Google Maps as normal and have its next turn, distance and a call alert appear on the Pod, with a free app that anyone can build and improve. The app does this by reading the Google Maps navigation notification on an Android phone and sending the matching screen to the Pod over Bluetooth. It was built by a person with no programming background, with an AI assistant writing the code, and tested step by step on a real bike. Every finding about how the Pod works is written down in `docs/` so others can extend it.

## Status
| Part | Status |
|---|---|
| Pairing with the PIN, reconnecting, keep-alive | Works |
| Turn arrows and distance from Google Maps | Works; some arrows are not mapped yet (docs/ICON_MAP.md) |
| Arrow colour by distance (yellow far, grey+red head, gradual red, flashing near) | Works |
| Unknown arrow | Shows the distance only, never a wrong old arrow |
| Call alert | Ferry icon shown during a call (the Pod has no call icon) |
| Compass | Dropped (first-generation Pod draws it distorted) |
| Google Maps in other languages | Not understood (use English) |

More: [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md), [docs/PROTOCOL.md](docs/PROTOCOL.md), [docs/ICON_MAP.md](docs/ICON_MAP.md), [docs/HISTORY.md](docs/HISTORY.md), [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md).

## What you need
- Windows PC, Android phone, USB cable
- Android Studio (free): https://developer.android.com/studio (about 1-2 GB download)
- Google Maps **in English** on the phone (the turn reader understands English text only)

## Build and install (about 30-45 minutes the first time)

1. **Install Android Studio** with the default options. Open it once and let it finish downloading its extras.
2. **Unzip** `TripperBridge.zip` somewhere simple, like `C:\TripperBridge`.
3. In Android Studio choose **Open**, pick the `TripperBridge` folder (the one with `settings.gradle` in it) and wait for "Gradle sync" to finish (first time: several minutes, needs internet). If it offers to update Gradle or the plugin, you can click *Don't remind me* or *Update*; both are fine.
4. **Phone:** Settings > About phone > tap *Build number* 7 times. Then Settings > Developer options > turn on **USB debugging**.
5. Plug the phone in by USB and tap **Allow** on the "Allow USB debugging?" prompt. Your phone should appear in the device list at the top of Android Studio.
6. Click the green **Run** (play) button. The app installs and opens on the phone.

If anything shows red errors: press **Build > Make Project**, copy the first error message in the Build panel, and paste it to Claude.

## First-time setup in the app
1. Tap **1. Allow permissions** and accept everything (Bluetooth/Nearby devices, Location, Phone, Notifications).
2. Tap **2. Allow notification access** and switch **Tripper Bridge** ON. (This is how it reads Google Maps.)
3. Tap **3. Battery** and set the app to *Unrestricted / Don't optimise*. Otherwise Android may stop it while riding.
4. Open the Play Store and check that **Motolog** and the official **Royal Enfield app** are not running in the background. Only one app can hold the Pod at a time.

## Pairing the Pod (first time only)
1. Tap **START bridge**.
2. Turn the **bike ignition on**. The Pod only advertises for about 30 seconds. If it does not connect, switch the ignition off and on again.
3. If the Pod shows a PIN, type it in the app and tap **Send PIN**.
4. If the Pod shows nothing and the status says it is connected, it did not ask for a PIN. That is fine.
5. If the Pod refuses to connect, the Pod may still be remembering the official RE app. Try **Forget pairing**, then restart the bridge and the ignition.

## Bench test (do this before riding, bike on the stand)
With the status showing "Connected to the Pod":
- **Test: turn left in 200 m**, **turn right in 1.2 km**, **roundabout 2nd exit**, **call alert**: each shows on the Pod for about 8 seconds. The call alert is the ferry picture.
- **Test: distance only** shows a distance with no arrow. **Colour explorer** steps through the colour byte (docs/PROTOCOL.md). **Icon cycle** buttons step through every Pod icon code (about 5 minutes each; the log lists the code shown).
- Start a route in Google Maps (while parked, bike off the road). The Pod should show a "route started" screen, then arrows and distances.
- Ring your phone from another phone: the ferry picture should appear while it rings.

Write down what each test did. If something is wrong, tap **Copy log** and paste it to Claude with one line such as "left-turn test showed a right arrow" or "Pod stayed on the clock".

## Riding day
1. Start Google Maps navigation as normal.
2. Open Tripper Bridge, tap **START bridge**, then turn the ignition on (or turn it on first and cycle it if it does not connect).
3. Keep the phone charging. The bridge keeps the phone awake and scans for Bluetooth, which drains the battery.
4. Tap **STOP bridge** when you finish. Do not use the phone while riding.

## What it does not do (yet)
See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md). In short: no compass, no next-turn arrow, no caller name, bottom line shows time remaining only, many arrow types still need a captured Google Maps picture, and if Google Maps changes its notification the reader may need an update (**Copy log** includes the raw notification text).

## Help add arrows
Run a route (fake GPS works), tap **Copy arrow pictures only** and open an issue or pull request with the pasted grids and what the Maps instruction was. docs/ICON_MAP.md lists the Pod code for each shape.

## If you ask another AI for help
Paste it this note first:

> Android app (Java, no libraries) that drives a Royal Enfield Tripper Pod over BLE. Pod name `RE_DISP`, service `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`, characteristic `01FF0101-...`. Packets are 20 bytes with CRC-16/CCITT-FALSE in the last 2 bytes. The Pod drops the link after 5 s without a write, so a ping or screen must be sent every 2 s. Do not invent packet bytes: they must come from `TripperProtocol.java`, which follows the WazeTripper project's `docs/PROTOCOL.md`. Files: `TripperProtocol` (packets), `MapsParser` (Google Maps text to icon), `ArrowClassifier` (Maps arrow picture to icon), `TripperService` (Bluetooth and keep-alive), `NavListener` (reads Google Maps notification), `MainActivity` (screen), `tools/FrameCheck.java` (PC checks). Read `HANDOFF.md` and `docs/ICON_MAP.md` first.

## Files
- `app/src/main/java/com/tripperbridge/` - the app code
- `docs/` - protocol notes, icon map, limitations, history, session log
- `tools/FrameCheck.java` - PC-side check of packets and parser (needs Java: `javac` TripperProtocol, MapsParser, ArrowClassifier plus this file)
- Protocol credit: WazeTripper by jnsdias (Apache-2.0), https://github.com/jnsdias/wazetripper

Not affiliated with Royal Enfield or Google. For personal use on your own bike and phone.

## Safety and privacy
This is an experimental, unofficial project. Use it at your own risk. Never look at or touch your phone while riding; set up your route before you start. It is not affiliated with or endorsed by Royal Enfield or Google. The app has no internet access and sends no data anywhere. It reads the Google Maps navigation notification on your phone and talks to your Tripper Pod over Bluetooth only. It asks for Bluetooth, location, phone-state and notification permissions solely to do that.

## Licence
MIT Licence. See the LICENSE file. The protocol notes credit WazeTripper (Apache-2.0).