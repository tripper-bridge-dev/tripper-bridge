# Pod icon map

Codes 00-4A were filmed by cycling every code on a first-generation Pod (video + log, day 2026). Codes 4B-FF show nothing (distance only); there is no call/phone icon. Left-hand/right-hand ring pictures: the ring with the exit head at top-left is the left-hand-traffic set used in India.

Status column: **Mapped** = the app shows this icon for a Google Maps arrow or text today. **Needs Maps picture** = the Pod can draw it, but the app has no sample of the matching Google Maps arrow yet (add one when an arrow picture is captured in the log, section "ARROW PICTURES SEEN"). **-** = not applicable.

| Code | Pod picture | Google Maps meaning | Status |
|---|---|---|---|
| 00 | destination pin on a stem | arrive / destination | Mapped |
| 01 | pin left of a dot on a stem | start / waypoint | Needs Maps picture |
| 02 | pin right of a dot on a stem | start / waypoint (mirror) | Needs Maps picture |
| 03, 04 | arrow up over a merging fork | lane / fork | Needs Maps picture |
| 05 | arrow with left fork branch | fork left (best guess) | Needs Maps picture |
| 06 | arrow with right fork branch | fork right (best guess) | Needs Maps picture |
| 07 | thick lane, branch left | ramp / lane | Needs Maps picture |
| 08 | thick lane, branch right | ramp / lane (mirror) | Needs Maps picture |
| 09 | straight arrow | continue straight / head | Mapped |
| 0A | ring, head top-left, no number | roundabout (left-hand set) | Mapped (text) |
| 0B-13 | same ring with exit 1-9 | roundabout, exit 1-9 (left-hand set) | Mapped (text: "take the 3rd exit") |
| 14 | left turn | turn left | Mapped |
| 15 | right turn | turn right | Mapped |
| 16 | sharp left (hook, head down-left) | sharp left | Needs Maps picture |
| 17 | sharp right | sharp right | Needs Maps picture |
| 18 | slight left | slight left | Mapped |
| 19 | slight right | slight right | Mapped (mirror) |
| 1A | U-turn, head down-right | U-turn (right) | Mapped |
| 1B | Y merge | merge | Mapped (shape unconfirmed on the road) |
| 1C | compass | - | Not used |
| 1D-25 | Y / two-lane fork variants | merge / lane variants | Needs Maps picture |
| 26 | ramp, head up-left | ramp / exit left | Needs Maps picture |
| 27 | keep right | keep right | Mapped |
| 28 | keep left | keep left | Mapped (mirror, unconfirmed on the road) |
| 29, 2A | fork variants | fork | Needs Maps picture |
| 2B, 2C | blank | - | - |
| 2D-30 | lane fork variants | lane | Needs Maps picture |
| 31 | ring, head top-right, no number | roundabout (right-hand set) | Used if the India tick is off |
| 32-3A | same ring, exit 1-9 | roundabout, exit 1-9 (right-hand set) | Used if the India tick is off |
| 3B | thick straight arrow | straight (variant) | Needs Maps picture |
| 3C | small pin | place / stop | Needs Maps picture |
| 3D | U-turn, head down-left | U-turn left | Mapped (mirror) |
| 3E | ferry | ferry. **This app uses it as the call alert.** | Mapped (text "ferry") |
| 3F | train | train / rail | Needs Maps picture |
| 40, 41 | pin with dotted trail | start / trail | Needs Maps picture |
| 42 | red wifi / signal | - | - |
| 43 | blank | - | - |
| 44 | low battery | - | - |
| 45 | red signal (inverted) | route started uses 45 | Mapped (route started screen) |
| 46-FF | blank (distance only) | - | - |

## Unknown arrows (Google Maps pictures not captured yet)
These Maps maneuvers are drawn by the Pod but the app cannot recognise the Maps picture yet, so the Pod shows the distance only: sharp left (16), sharp right (17), left U-turn variants, fork left/right (05/06), ramp/exit left (26) and right (code not found; candidates 29, 2A), solid straight (3B), train (3F), waypoint pins (01, 02, 3C, 40, 41), the lane pictures (03, 04, 07, 08, 1D-25, 2D-30). Roundabout pictures are not needed (the instruction text gives the exit number).

How to add one: run a route on a phone with the app in the background, tap **Copy arrow pictures only** (these are 32 x 32 grids of the unrecognised Maps arrows), and add the grid to `ArrowClassifier.java` with the code from this table. Left/right versions of the same shape are exact mirrors, so one sample covers both.
