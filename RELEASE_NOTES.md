## BrightRecorder v1.1 — Louder, knows where it is, and the wheel plays

**Three things reported against v1.0: recordings came back too quiet, clips were all filed under
"Somewhere", and pressing the wheel in did nothing.**

### Recordings are louder

The microphone is opened as `UNPROCESSED`, which is the right source for recording a room — it
turns off the noise suppression, the high-pass and the automatic gain control that would
otherwise be fighting the recording. It is also why everything came back quiet: with the AGC
gone, nothing was making it loud, so a quiet room recorded honestly and unlistenably.

There is now makeup gain on the way to disk — four times, +12 dB — with a look-ahead limiter
under it. A flat multiply loud enough to lift a quiet room is far too much for a passing
motorbike, and a clipped sample is clipped in the file for good, so the limiter holds peaks just
under full scale instead of letting them square off. Quiet material gets the whole makeup; loud
material gets whatever fits, smoothly. The limiter is BrightNoise's, which had already been
beaten into shape against dense transients like heavy rain, where a fast release pumps audibly.

Applied on the record path rather than at playback, deliberately, so a clip copied off the phone
is loud too. The level meter now reads the signal after limiting, so what you see is what is
being written.

### Clips get a place again

This was a plain bug, not a missing feature. The location lookup and the permission prompt start
at the same instant, so on the very first recording the lookup ran before any grant existed,
found no permission, and gave up immediately. Nothing retried it. So the first clip was filed
under "Somewhere" — and so was every clip after it, because the grant was only ever picked up by
a later launch of the app.

The permission result is now acted on: say yes and the lookup starts again against the recording
still in progress. Nothing about the design changed — the fix is telling the controller that the
answer arrived.

Still coarse location only, still looked up during the recording rather than before it, and a
clip with no fix is still filed under "Somewhere" rather than a guess.

### Press the wheel to play

The wheel already shuttles the tape, so pressing it in now starts and stops it. Handled on the
way down so it answers under the thumb, and only on the first event of a press — a held button
auto-repeats, which would otherwise toggle play a dozen times.

One thing to know if it seems dead: **LightControl claims the wheel click phone-wide** and
deliberately passes only bare turns through to apps. Where it is installed with the click bound,
that binding wins and this one never sees the event. Unbind the click there and this works.

Recognising the press also tightened the key handling. The turns come from the optical sensor and
the press from the board's `gpio-keys`, so scancode trust is now per-code rather than one shared
set of devices — otherwise a paired Bluetooth keyboard's F8 would start playback and its `r`
would shuttle the tape.

### Under the hood

61 unit tests, up from 54. The new ones pin both halves of the gain, because they pull against
each other: that a quiet room really does come up by roughly the full makeup, and that nothing
clips at any input level from 1% to full scale — plus that a loud bang does not leave the
following minute ducked, and that dense transients do not make the level breathe.

Fixes [light-reports] — recordings too quiet, no location on clips, wheel press did nothing.
