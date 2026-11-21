# Known limitations (version 0.8)

- **No compass.** The first-generation Pod draws it distorted, so it was dropped. Idle = the Pod's own clock.
- **No next-turn small arrow.** The Google Maps notification only has the current step; the "Then" arrow exists only inside the Maps app.
- **No time left on some phones** (Android 17 phones sometimes omit the arrival time in the notification).
- **No steady full red at 0 m.** The Pod has no such value; the closest is the flashing near-full red used at 20 m or less.
- **Call alert shows no caller name.** The Pod has no call icon, so the ferry picture is used. This means a real ferry instruction in Google Maps shows the same picture.
- **Many arrow types are not mapped yet** (see ICON_MAP.md). An unknown arrow shows the distance only. The Y-merge and mirrored icons are unconfirmed on the road.
- **Screen waking.** On one phone the screen turns on by itself; no wake code exists in the app (likely a phone setting: lift/tap to wake). Not resolved.
- **Random disconnects** (about every minute on some rides; the arrow stays and the link returns within seconds). An optional GPS keep-alive switch was added in 0.8; one test run with it showed no disconnects, but this is not proven. Idea not tried: resend the clock every 30 s.
- **Language.** Google Maps text in other languages is not understood (use English). Arrow pictures are language independent.
- **Install.** Google Play Protect can block the sideloaded app on some phones; install with USB debugging, or turn Play Protect scanning off temporarily.
