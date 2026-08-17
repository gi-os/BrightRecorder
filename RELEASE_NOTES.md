## BrightRecorder v1.2 — Winding works like winding

**Rewind and fast-forward are momentary now, from the keys and from the wheel, and letting go
hands the tape straight back to whatever it was doing. Clip titles are places, never
coordinates.**

### The wheel winds, and keeps winding

Turning the wheel back rewinds. Turning it forward fast-forwards. It keeps winding as long as
you keep turning and lets go 350 ms after the last notch — long enough to ride over the gaps in
an unhurried turn, short enough that stopping feels like stopping.

What was there before was a flywheel: the app inferred a *speed* from how thickly the notches
arrived, so the tape had mass, coasted, and settled back on its own. It reads well written down
and it is wrong in the hand. What you want from a wheel on a tape machine is to wind until you
get there — not to nudge a speed and then wait for it to decay. The flywheel and its tests are
gone rather than left switched off.

### Letting go carries on playing

The wind keys were latching: press rewind and it rewound until you pressed something else, so
finding a moment mid-clip meant winding, noticing you had arrived, and pressing play again.

They are momentary now. Hold rewind — or keep turning the wheel — and the tape winds; let go and
it carries on with exactly what it was doing before. If it was playing, it plays. If it was
stopped, it stops. That is the behaviour people mean by "like a tape recorder", and it is the
one thing a latching button cannot do.

Both controls run through the same `WindLatch`, so they cannot drift apart in what they resume
to, and it is free of Android so "what does it go back to" has ten unit tests rather than a
phone. It never resumes into another wind, never into a recording, and a wind that runs off
either end of the tape forgets its resume rather than starting playback from an end.

### Titles are places

Clip names were falling back to coordinates whenever the geocoder could not answer. A list of
clips called `48.8570, 2.3700` tells you where you were only if you go and look it up, which is
exactly the work this app exists to save.

Names are now always **somewhere, city** — `Café de Flore, Paris`, `Rue de Lappe, Paris`,
`Kreuzberg, Berlin`. The most specific named thing the geocoder found wins, then the street it
sits on, then the neighbourhood. No house numbers, no postcodes, no country. And no
coordinates: a clip nobody could name is `Somewhere`, which at least reads as a place you have
been.

Worth knowing about the ceiling here: Android's `Geocoder` is a reverse geocoder, not a places
search, so it names what is *at* the fix rather than what is interesting nearby. A café comes
back when the fix lands on it. Naming the nearest notable thing regardless would mean the Places
API — a key, an account, and a billable lookup on every recording.

### If the wheel still does nothing, it is LightControl

This app cannot fix that from inside, and it is worth stating plainly because both wheel
gestures are claimed phone-wide by default:

| Gesture | LightControl default | What happens |
| --- | --- | --- |
| Bare turn | `unknownAppTurn = BRIGHTNESS` | The turn becomes a brightness step and is consumed |
| Wheel click | `WheelClick` / Tap → `Torch` | The press lights the torch and is consumed |

Anything that is not `PassThrough` consumes the key, so neither gesture ever reaches this app.
`com.gios.` is already in LightControl's scroll-aware list, but that rule still yields brightness
while the turn mode is set to brightness, and it keeps the click for LightControl regardless.

**The one setting that fixes both: give BrightRecorder the "Off" (hands-off) app rule in
LightControl** — every key goes to the app untouched, which is what Light's own tools get.
Setting the global turn mode to `PASS THROUGH` fixes the turns but leaves the click on the torch.

### Under the hood

61 tests. The ten that covered the flywheel are replaced by ten covering what winding resumes
to, which is the part that is now load-bearing.
