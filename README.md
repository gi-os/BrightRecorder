# BrightRecorder

A tape recorder for the **Light Phone III**. Record a moment, and it becomes a clip
named for where you were and when. Wind through the whole lot with the brightness
wheel, forwards or backwards — and hear it as you wind, the way a tape machine lets
you. Let go and it carries on playing from where you landed.

Keep as many tapes as you like — one for a trip, one for the flat, one for the year —
each with a name and a pattern on its label, and swipe between them on the shelf.

It is not a voice recorder. There is no transcription, no trimming, no waveform
editor and no file manager. It records rooms, streets, weather and rain on windows,
and the only thing it does with them is let you find them again.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightRecorder.png" alt="Scan to open BrightRecorder in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightRecorder there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse every
Bright app, at
**[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html)**.

Repo name **BrightRecorder**, launcher label **Recorder**, applicationId
`com.gios.brightrecorder`.

**Current release: v1.7.9** (tag `v1.7.9`).

## Install by hand

Grab the APK from the [latest release](../../releases/latest) and sideload it:

```bash
adb install -r BrightRecorder-v1.4.<run>.apk
```

Every push to `main` publishes a signed release, so `-r` upgrades in place.

One stable key signs every build (`keystore/brightrecorder.jks`, committed on purpose)
and exactly one APK ships per release, which is what Obtainium and the index need. The
certificate SHA-256 is pinned in `signing-fingerprint.txt` and CI fails on drift — a
changed certificate otherwise surfaces only as `Failure: Invalid` at install time.

## Tapes

You keep more than one. A tape for a trip, a tape for the flat, a tape for the year — they
stay separate, and the machine has exactly one on it at a time. Each has a name you give it
and a **pattern** on its label, which is what colour would be if this panel had any; the
SHELF screen swipes through them.

A tape is a directory of clips and nothing more:

```
tapes/2026-08-17 143205 Trip to Rome/2026-08-17 143912 Trastevere, Rome.wav
```

Filed the way clips are — timestamp first so the shelf sorts by when each tape was started,
human name after — so renaming is renaming the folder, and the store reads as itself on a
desktop. The pattern is a one-line file inside the folder, the only thing here that lives
outside a filename: derived from the name, a rename would repaint the tape; put in the folder
name, changing it would rewrite the tape's identity.

`Tapes.delete` refuses a tape that still has clips on it. Emptying it one clip at a time is
the only way, because a recursive delete across a store of recordings is the single
unrecoverable mistake this app could make.

## The tape

The idea the whole app rests on is that, within a tape, there are no separate files to play.
Every clip is butted against the next one in recording order, and the machine addresses all of
them as **one continuous tape** with a single position running from the first sample to the
last.

That is not a metaphor bolted on afterwards, it is how the code works, and it is what
makes the rest fall out for free:

- Playing to the end of a clip runs straight into the next one. No gap, no track change,
  no "next" button — the end of a clip is simply a position like any other.
- Winding back past the start of a clip lands in the one before it.
- A position is a sample number, so scrubbing is arithmetic rather than a seek table.

`Timeline` owns that mapping, `TapeHead` turns a tape position into a read from whichever
file it falls in, and `TapeEngine` is then a plain loop: read the rate, read a sample at
the position, move the position by the rate. Everything the machine does is a change to
one signed number.

| Rate | What it is |
|---|---|
| `1.0` | Play |
| `-4.0` | Rewind, audible |
| `4.0` | Fast forward, audible |
| `0` | Stopped |

Winding is the same operation as playing, at four times the speed and — for rewind — with
the sign flipped. There is no separate rewind routine anywhere in the app.

## The wheel

The LPIII's brightness wheel is a `Pixart pat9126ja` optical sensor that emits one key
event per notch, roughly every 35 ms while it is turning. It reports notches, not
position, and it says nothing about how fast it is being turned.

**Turning the wheel moves the tape, at the speed you turn it.** Back to go back, forward to
go forward, and it keeps going for as long as you keep turning. Each notch is worth a fixed
length of tape, so turning twice as fast covers twice as much ground: a hard spin reaches
about 8x, an unhurried turn sits near 1.2x.

Crucially the wheel does **not** change the transport. It contributes a rate that overrides
the tape speed while it is turning, and nothing else — so scrolling while playing leaves it
playing, and the wind keys never light up. An earlier version did switch the transport into
rewind and back out on a timeout, and any turn slower than that timeout flipped it in and out
on every notch: the keys blinked and playback stuttered. Scrolling is a hand on the reel, not
a mode change.

`Scrub` is that rate, and it has no Android in it, so the feel of the wheel is unit-tested —
including the specific fault above: a slow steady turn must never drop the tape to a
standstill between notches.

**Tap the wheel to play or stop; hold it to record.** The wheel is the only control on this
phone you can work without looking at it, so it carries both of the things you do without
looking. Four-tenths of a second is the line between a tap and a hold — long enough that
pressing play never records by accident, short enough to answer while your thumb is still
deciding. Pressing it again stops the recording, on the way *down* rather than on the release,
because stopping a recording should happen the instant you ask for it.

`Press` is that decision, and it has no clock of its own: the caller supplies the events and the
timer, so every branch of it is unit-tested.

> **LightControl arbitrates the wheel phone-wide.** Its defaults bind the click to the torch and
> turn bare notches into brightness, and anything that is not `PassThrough` consumes the key — so
> without it agreeing, neither gesture reaches any app. LightControl now treats this app as one
> that owns the whole wheel; see "Getting the wheel past LightControl" below.

The press is a `gpio-keys` button rather than the optical sensor, so scancode trust is per
code rather than per device: 66 is only honoured from the board's buttons and 19 and 20 only
from the sensor. Without that, a paired Bluetooth keyboard's F8 starts playback and its `r`
winds the tape.

## Winding, and getting the wheel past LightControl


Rewind and fast-forward are **momentary**. Hold the key and the tape winds; let go and it carries
straight on with whatever it was doing before. Wind out of the middle of a clip while playing, let
go, and it keeps playing from where you landed. You never press play again, which is the entire
point and the thing a latching button cannot do.

`WindLatch` is what remembers the interrupted state, and `Deck` is every rule about when it
applies. Both are deliberately free of Android so that "what does the tape do next" has a unit
test rather than a phone. A wind never resumes into another wind, and never into a recording — a
recording is filed the moment it stops, so there is nothing to go back to.

**Only the keys use the latch.** The wheel does not, and that separation was learned the hard way:
while they shared one, the wheel's idle timer could end a wind a *key* was still holding, so
rewind quit early, playback resumed under your finger, and letting go then did nothing because
the latch was already spent.

**There is one transport and the engine does not copy it.** It used to, and that was the fault
behind four releases of "letting go of rewind does not carry on playing": the copy was written from
five places, one of them the audio thread reporting the end of the tape, and letting go at the end
of a wind is exactly when those two collide. Whichever wrote last won, and when the audio thread
won the tape stopped and nothing afterwards corrected it. `TapeEngine.transport` is now a property
that reads the deck, so there is no copy to go stale.

**The front of the tape is a wall, not a stop.** The reels stop against it, because there is no
more tape to wind, and that is all that happens — the key is still down and what it interrupted is
still waiting, so letting go plays on from the beginning. Reaching it used to cancel the wind and
its resume together, which sounds like an edge case and is not: a moment is a few seconds long and
rewind runs at 4x, so a rewind started anywhere in a clip reaches the front almost every time. The
other end genuinely is the end, so running off it stops and forgets the resume.

That asymmetry is why `Deck` exists. The transport used to be a field four threads wrote to — the
keys from composition, the wheel from the input thread, the end of the tape from the audio thread,
the recorder from a coroutine — with the latch beside it and nothing holding the two together, and
three releases running tried to fix the same reported fault by guessing at an interleaving.

**If the wheel does nothing in this app, look at LightControl first.** Its defaults claim both
gestures phone-wide:

| Gesture | LightControl default | Effect here |
|---|---|---|
| Bare turn | `unknownAppTurn = BRIGHTNESS` | Turn is converted to a brightness step and consumed |
| Wheel click | `WheelClick` / Tap → `Torch` | Press lights the torch and is consumed |

`com.gios.` is already in LightControl's `scrollAwarePrefixes`, so this app resolves to
`ScrollThrough` — but that still yields `Brightness` while the turn mode is set to brightness,
and `ScrollThrough` keeps the click for LightControl either way.

LightControl now resolves `com.gios.brightrecorder` to its hands-off rule by default, through a
list of apps that own the whole wheel rather than only its turns — so both gestures arrive here
with no setting to find. The per-app rule **Off** does the same thing by hand for anything else,
and setting the global turn mode to `PASS THROUGH` fixes turns alone while leaving the click on
the torch.

## Winding sounds like winding

Playing faster than 1x means skipping samples, and skipping samples is aliasing: content
above a quarter of the sample rate folds back down and arrives as a metallic whistle that
is not in the recording. Past about 5x it turns speech into a modem.

The fix is to average across the samples being skipped rather than picking one, because a
moving average is a low-pass filter and this puts it exactly where the aliasing is made.
It is also what the physical object did — a tape head reads a finite length of tape at
once, so winding at speed came out dull rather than shrill. The honest emulation and the
correct signal processing turn out to be the same operation.

Below 1x the problem is the opposite one and the answer is linear interpolation, so a slow
crawl is smooth instead of a staircase.

## Names

A clip is named for where and when it was recorded, and nothing else. No renaming, no
tags, no "Recording 14" — the two things you remember about a moment are where you were
and roughly when, so that is the whole filing system.

On disk the timestamp leads, because the directory has to sort chronologically for the
tape to be in order:

```
2026-08-17 143205 Bastille, Paris.wav
```

On screen the place leads, because that is what you scan a list for:

```
Bastille, Paris at 17 Aug 2026, 14:32
```

There is no database and no index file. The tape is whatever is in the directory, and
everything about a clip except its length is recovered from its filename — so copying the
tape off the phone and back loses nothing.

The name is always **somewhere, city** — `Café de Flore, Paris`, `Rue de Lappe, Paris`,
`Kreuzberg, Berlin` — because that is how a person says where they were. The most specific
named thing the geocoder found wins, then the street, then the neighbourhood, then the city,
and past that the state and the country. Never a house number, never a postcode.

**Never coordinates.** They used to be the fallback when the geocoder could not answer, and
they are worse than nothing: a list of clips titled `48.8570, 2.3700` tells you where you
were only if you go and look it up, which is the work this app exists to save.

**And never `Somewhere`**, which is worse still — a tape of fourteen clips all called
`Somewhere` is the filing system failing at the only job it has. The cause was timing rather
than the lookup. Recording starts the microphone in the same frame you press it, a fix takes
anywhere from a second to never, and a moment is four seconds long — so the clip was filed
before the answer arrived, every time. Four things fix that, and it takes all four:

- **The fix is kept warm.** The lookup runs when the app comes to the front, not when you press
  record, so there is usually already an answer. Repeating it is free: one less than five
  minutes old is where you are now, and looking again returns immediately.
- **The place is not forgotten between recordings.** The phone has not moved since you pressed
  stop.
- **A stale position beats none.** A cached fix from an hour ago is the wrong street and the
  right city, and the city is what goes in the title. Indoors, with nothing else on the phone
  asking for a position, it is the only thing that ever answers.
- **There is a floor.** The phone's time zone names a city, the mobile network names a country,
  and the locale names one behind that — none needing a permission, a network request or a
  position. `Europe/Paris` is a better guess for where you were than nothing is. The network's
  country outranks the locale's, because it is where the phone *is* rather than where it is
  configured to think it is.

A clip filed under one of those guesses **gets its real name later**. The lookup keeps going for
a minute and a half after you stop, and when it lands the clip is renamed. There is no index to
update — the tape *is* the directory — so the rename is the whole of it, which is what filing by
filename was for. A `Place` carries how well it is known so the two can be told apart.

One limit worth knowing: Android's `Geocoder` is a reverse geocoder, not a places search, so
it names what is *at* the fix rather than what is interesting nearby. A café comes back when
the fix lands on it. Naming the nearest notable thing would mean the Places API — a key, an
account, and a billable lookup per recording — which is a different app from this one.

## Loudness

Every clip is measured once and played back through a gain that brings it to the same loudness as
every other clip. A tape of moments is recorded in whatever the room was doing at the time — a
kitchen, a street, a train, a room with one person in it — and left alone those come back twenty
decibels apart. A fixed makeup gain on the record path cannot fix that, because it does not know
how loud the room was until the recording is over.

The measurement is **ITU-R BS.1770 integrated loudness**, the one streaming services use. Not a
peak: two recordings with identical peaks can be twenty decibels apart to listen to. Not an RMS
either: that counts a lorry passing at 40 Hz as loudly as a voice. Three parts of it earn their
keep — K-weighting, which discounts what the ear is not sensitive to; overlapping 400 ms blocks,
so a loud moment straddling a boundary is not averaged away; and two gates, which throw out the
silence and then the merely quiet. Without the gates a four-second clip with three seconds of room
tone in it reads as almost silent and gets turned up until the noise floor is a wall, which is
exactly the material this app records.

**The target is −16 LUFS**, and it is the one number to turn. Streaming services normalise to −14,
and that figure is quoted for *stereo* material; a mono channel measured alone reads 3.01 LU lower
for the same waveform, so the mono equivalent is about −17. This sits a decibel louder on purpose.

Two limits keep it sensible. No clip is turned up more than 20 dB, or a recording of a silent room
becomes a recording of a microphone's noise floor. And the playback limiter is never asked to pull
down more than 12 dB — loudness is an average and peaks are not, so a quiet room with one door slam
in it is far below the target on average with its loudest sample already at the ceiling, and a
limiter swallowing all of that ducks the whole recording around every transient. Clips with
headroom hit the target exactly; spiky ones land short of it and stay clean.

**Nothing is rewritten.** The gain is applied on playback and the samples on disk are untouched, so
this cannot damage a recording that cannot be made again, it applies to everything recorded before
it existed, and changing the target re-levels the whole tape with no files rewritten.

The measurement lives in a chunk after the samples in the clip's own WAV, which keeps the property
the app rests on: there is no index anywhere that could disagree with a recording. A clip copied
off the phone and back brings its measurement with it, and every other program skips the chunk
without noticing. Measuring is a pass over the audio, so it happens in the background a clip at a
time rather than on the launch path — the tape plays throughout, and each clip reaches its proper
level as its own measurement lands.

## Why uncompressed

22050 Hz, 16-bit, mono WAV. Every part of that is chosen for scrubbing rather than for
fidelity:

- **Uncompressed**, because a tape you can spin has to be addressable by sample. In AAC or
  Opus the audio only decodes forwards from a keyframe, so reverse means decoding forwards
  repeatedly and discarding most of it. With raw PCM, sample *n* is at a known offset and
  reverse is a negative step.
- **Mono**, because there is one microphone and halving the data halves every seek.
- **22050 Hz**, which is honest about what this is — a tape recorder for moments, not a
  field recorder for masters. It also keeps a clip at 2.6 MB a minute, so an hour is 155 MB
  on a phone that has not got much to spare. At 44100 the same hour is 310 MB.

The microphone is opened as `UNPROCESSED` where the device allows it. The defaults are
tuned for speech — noise suppression, AGC, a high-pass around 100 Hz — and every one of
those is wrong here: this app records rain, traffic and rooms, and a noise suppressor hears
all of that as noise and removes precisely what was being recorded.

That has one consequence worth stating, because it was reported as a bug in v1.0: with the
automatic gain control gone, nothing is making the recording loud, so a quiet room comes back
honest and far too quiet to listen to on a phone speaker. So there is **makeup gain on the way
to disk** — four times, +12 dB — with a look-ahead limiter under it, holding peaks just below
full scale rather than letting them square off. Quiet material gets the whole makeup, loud
material gets whatever fits. It is applied while recording rather than at playback so that a
clip copied off the phone is loud too, and a clipped sample would be clipped in the file for
good. `RecordGain.MAKEUP` is the one number to turn if it is still not enough.

## A recording is never lost

The failure this design is built around is the app dying mid-recording, because these
recordings cannot be made again.

Samples are written to disk as they arrive. RIFF stores its own length in the header, which
is not known until recording stops, so the length starts as a placeholder and is patched
afterwards. If the process dies the placeholder is wrong but every sample is already on
disk — so the temporary filename carries the start time, and on the next launch the header
is rebuilt from the file size and the clip is filed properly. Nothing is lost but the place
name, which died with the process.

Using the file's modification time instead would have said when recording *stopped*, which
files a clip under the moment the battery died.

## Shake to report

Shake the phone — there and back, twice — and a sheet comes up: pick what happened from
five chips, add a note in your own words, send. It becomes an issue in `gi-os/light-reports`
labelled `recorder`, carrying the screen you were on, app and firmware versions, free
space, heap, and the stack trace if the app died last time.

The app also reports its own noticed failures, which matters more here than in most of
these apps: a microphone that never opened looks exactly like a microphone recording an
empty room, and by the time anyone notices, the moment has gone.

Reports queue on disk before anything is sent, so a phone with no signal keeps them until
it has one.

## Building

```bash
./gradlew :app:assembleRelease
```

Needs JDK 17+ and the Android SDK with `build-tools;35.0.0`. `aapt2` is x86_64-only on
Linux, so an aarch64 machine cannot build this at all — push a branch and read `check.yml`
instead, which runs the same compile and tests without publishing anything.

The parts worth testing are the parts with no Android imports — the timeline, the shuttle,
the resampler, the naming and the WAV container — and they are unit tested on the JVM,
because a resampling index that drifts one sample per block is inaudible for a minute and a
rising whine after ten.

```bash
./gradlew :app:testDebugUnitTest
```

## Version history

| Version | What changed |
|---|---|
| v1.7.9 | Letting go of rewind or fast-forward carries on playing. The engine reads the transport from the deck instead of keeping a copy the audio thread could clobber, and the wind keys issue their release from a `finally` so it cannot be lost. |
| v1.6.8 | Every clip is measured for loudness and played back at the same level, about as loud as music on a streaming service — new recordings and everything already on the tape, with nothing rewritten. |
| v1.5.7 | Hold the wheel to record. Rewinding to the front of the tape and letting go carries on playing — the transport rules moved into `Deck`, away from Android, with a test each. A clip is never filed under "Somewhere": the fix is kept warm, a stale one beats none, the time zone is the floor, and a clip named by a guess is renamed when the real name lands. |
| v1.4.5 | The wheel scrubs at a speed that follows how fast you turn, instead of switching the transport in and out and blinking the wind keys. |
| v1.3.4 | A shelf of tapes: name them, mark them with a pattern, swipe through them, load the one you want to record onto. Everything already recorded moves onto the first tape. |
| v1.2.3 | Winding is momentary from both the keys and the wheel, and hands the tape back to what it was doing. Clip titles are places, never coordinates. |
| v1.1.2 | Makeup gain and a limiter on the record path, so recordings are loud. Location actually gets collected — the permission result was being ignored. Pressing the wheel in plays and stops. |
| v1.0.1 | First release. |

## Licence

MIT. See [LICENSE](LICENSE).
