## BrightRecorder v1.6 — Every clip the same loudness

**Recordings were still too quiet, and no two of them were quiet by the same amount. Both are
fixed, for new recordings and for everything already on the tape.**

### Levelled, not just louder

A tape of moments is recorded in whatever the room was doing at the time: a kitchen at breakfast, a
street, a train, a room with one person in it. Left alone those come back twenty decibels apart, so
listening to a tape end to end meant riding the volume — which is the one thing this app exists for
not having to do. A fixed makeup gain on the record path cannot fix that, because it does not know
how loud the room is until the recording is over.

So every clip is now **measured** once and played back through a gain that brings it to the same
place. The measurement is ITU-R BS.1770 integrated loudness — the one streaming services use — not
a peak and not an RMS, because two recordings with identical peaks can be twenty decibels apart to
listen to, and plain RMS counts a lorry passing at 40 Hz as loudly as a voice.

Three parts of it earn their keep. **K-weighting** discounts rumble you cannot hear and lifts the
range the ear is most sensitive to. **Overlapping 400 ms blocks** stop a loud moment straddling a
window boundary from being averaged away. And **two gates** throw out the silence, and then the
merely quiet: without them a four-second clip with three seconds of room tone in it reads as almost
silent and gets turned up until the noise floor is a wall — which is exactly the material this app
records.

### How loud

The target is **−16 LUFS**, and it is one constant with the reasoning written above it.

Streaming services normalise to −14 LUFS, and that figure is quoted for *stereo* material. A mono
channel measured on its own reads 3.01 LU lower for the same waveform, so the mono equivalent of
−14 stereo is about −17. This sits a decibel louder than that on purpose: the ask was for it to be
a little louder than music rather than exactly level with it, so the phone's own volume never has
to move.

Two limits stop that becoming silly. A clip is never turned up by more than 20 dB, because a
recording made in a genuinely silent room would otherwise have the microphone's own noise floor
lifted to conversational level — a clip that used to be quiet becoming a clip that is loudly
nothing. And the playback limiter is never asked to pull down more than 12 dB: loudness is an
average and peaks are not, so a quiet room with one door slam in it can be far below the target on
average while its loudest sample is already at the ceiling, and a limiter swallowing all of that
is audible as the whole recording ducking around every transient. Clips with headroom reach the
target exactly; spiky ones land short of it and stay clean, which is the right way round.

### And retroactively

Everything already recorded is measured too, in the background, the next time you open the app.
Nothing is rewritten — the samples on disk are untouched — so this cannot damage a recording that
cannot be made again, and if the target ever changes every clip re-levels with no files rewritten.

The measurement is stored in the clip's own WAV, in a chunk after the samples. That keeps the
property the whole app rests on: **there is no index anywhere that could disagree with a
recording.** A clip copied off the phone and back brings its measurement with it, and any other
program skips the chunk without noticing it.

The pass is deliberately not on the launch path. A tape of a few hundred clips is tens of megabytes
to read, so it happens a clip at a time with a pause between, the tape plays throughout, and each
clip starts playing at its proper level as soon as its own measurement lands.

### One bug found on the way

`repair` — the thing that mends a recording the process died in the middle of — inferred the length
of the audio from the size of the file. That was already wrong for a clip that had been through a
desktop editor, which comes back with a chunk of software credits in front of its data: the credits
would have been counted as half a second of noise at the head of the clip. Storing a measurement
after the samples would have made it wrong for every clip on the tape. It now checks what it is
looking at before it rewrites anything.

### Under the hood

- `Loudness` — BS.1770 gated loudness, with the K-weighting coefficients recomputed from the
  analog prototype for 22050 Hz. The tests check that asking it for 48 kHz reproduces the spec's
  published coefficients exactly, and that a 997 Hz sine at −20 dBFS reads −23 LUFS, which is the
  spec's own calibration.
- `Levels` — the target and the two limits, with the reasoning for each.
- `Wav.readLevel` / `writeLevel` — the measurement in the clip's own file.
- `Library.measure` — one pass over a clip's samples.
- 169 tests, up from 125.

---

## BrightRecorder v1.5 — Hold the wheel to record

**Three things reported against v1.4: the wheel should record when you hold it, rewinding while
playing still did not carry on playing when you let go, and clips were still being filed under
"Somewhere".**

### Hold the wheel to record, press it to stop

The wheel is the only control on this phone you can work without looking at it, so it now carries
both of the things you do without looking. A tap plays and stops, as before. **Holding it for
four-tenths of a second starts recording**, and pressing it again stops — you do not have to hold
it a second time, because by then you are holding a phone at a moment you are trying not to
interrupt, and a second timed gesture is a thing to get wrong under pressure.

Pressing the wheel while a recording is running stops it on the way *down*, not on the release.
Stopping a recording is the one thing that should happen the instant you ask for it.

You still need **LightControl 2.15 or later** for any of the wheel gestures to reach this app.
Before that it kept the click for the torch phone-wide, and nothing here could outrank it.

### Rewinding to the front of the tape, properly this time

This was reported against v1.2, v1.3 and v1.4. Each fix found a real bug and none of them found
*this* one, which was the one doing the damage.

Reaching the front of the tape used to cancel the rewind **and the thing it interrupted, together**.
So letting go left the tape stopped at the beginning. That sounds like an edge case and is not: a
moment is a few seconds long and rewind runs at 4x, so a rewind started anywhere in a clip reaches
the front of the tape almost every time you use it. "Rewind while playing, then let go" ended in
silence very nearly always.

The front of the tape is a wall, not a stop. The reels stop against it, because there is no more
tape to wind — and that is all that happens. The key is still down and what it interrupted is still
waiting behind it, so letting go plays on from the beginning, which is what the machine this
imitates does.

**Why it took three goes.** The transport was a field four different threads wrote to — the keys
from the screen, the wheel from the input thread, the end of the tape from the audio thread, the
recorder from a coroutine — with the resume latch beside it and nothing holding the two together.
Every fix was a guess about an interleaving, because nothing about it could be tested without a
phone in your hand. So the rules now live in one class, `Deck`, with no Android in it, one lock,
and a test for each rule — including the one above, which fails against every previous release.
The controller no longer decides anything; it carries out.

Two more faults fell out of writing them down. The end of the tape was being announced to the
audio thread forty times a second once the head was parked against it, rather than once when it
arrived. And the poll that drives the counter tore the render thread down while still holding the
slot that would have let a key press start it again — a tape that looks like it is playing, and is
silent.

### A clip is never called "Somewhere" again

"Somewhere" was honest and completely useless. A tape of fourteen clips all called "Somewhere" is
the filing system failing at the only job it has.

The cause was timing, not the lookup. The hunt for a place name started when you pressed record,
and a moment is four seconds long — so the clip was filed long before the first fix arrived, every
single time. Four things fix it, and it takes all four:

- **The fix is kept warm.** The lookup runs when the app comes to the front, so by the time you
  press record there is usually already an answer. It costs nothing to repeat: an answer less than
  five minutes old is where you are now, and looking again returns immediately.
- **The place is no longer forgotten between recordings.** The phone has not moved since you
  pressed stop, and what was already found is the one answer certain to be ready in time.
- **A stale position beats none.** A cached fix from an hour ago is the wrong street and the right
  city, and the city is what goes in the title. Indoors, with nothing else on the phone asking for
  a position, it is the only thing that ever answers.
- **There is a floor.** Failing all of that, the phone's time zone names a city, the mobile
  network names a country, and the locale names one behind that — none of which needs a
  permission, a network request or a position. `Europe/Paris` is a better guess for where you were
  than nothing is. (A phone reporting UTC with no SIM and no locale country is the one case that
  still has nothing to say, and it is documented rather than invented around.)

The name chain also carries on further down now — past the street and the neighbourhood to the
city, the state, and the country — instead of giving up when the geocoder returns an address with
no city in it.

**And a clip filed under a guess gets its real name later.** The lookup keeps going for a minute
and a half after you stop recording, and when it lands the clip is renamed. There is no database
to update — the tape *is* the directory — so the rename is the whole of it, which is what filing
by filename was for.

### Under the hood

- `Deck` — the transport and its rules, away from Android and unit-tested.
- `Press` — what a press of the wheel means, decided without a clock so a test can supply one.
- `Place` and `Coarse` — a place name that says how well it is known, and the floor under it.
- `Library.rename` — refiling a clip under the place that turned up late.
- 122 tests, up from 83.

---

## BrightRecorder v1.4 — The wheel stops blinking

**Three things reported against v1.3: the wheel flashed the wind keys on and off as you scrolled,
rewinding while playing did not carry on playing when you let go, and the wheel press still lights
the torch.**

The first two were the same bug.

### Scrolling is a speed, not a mode

Turning the wheel used to switch the machine *into rewind* and switch it back out again a third of
a second after the last notch. Any turn slower than that timeout flipped the transport in and out
on every notch — the rewind key lighting up, going out, lighting up — and because coming out of a
wind means "resume what you were doing", a scroll while playing became a stutter of stop, start,
stop, start.

The wheel no longer touches the transport at all. A notch now only contributes a **rate**, which
overrides the tape speed for as long as the wheel keeps moving. Scroll while playing and it is
still playing; the head simply moves faster, and slides back to 1x when you stop, with nothing to
resume because nothing was interrupted. The keys cannot blink because their state never changes.

Speed follows how fast you turn. The sensor reports one notch at a time and no speed, so the speed
is the gap between notches: each notch is worth a fixed length of tape, so turning twice as fast
covers twice as much ground per second. A hard spin reaches about 8x; an unhurried turn sits near
1.2x, which is the speed for finding a word. It eases in from rest rather than starting at
whatever the last turn ended on, and it ramps down when you stop rather than cutting, because the
head has to keep moving continuously — a jump in a waveform is a step, and a step is a click.

### Rewinding while playing carries on playing

Same root cause. The wheel and the wind keys shared one latch, so the wheel's idle timer could end
a wind **that a key was still holding**: rewind stopped early, playback resumed under your finger,
and letting go then did nothing because the latch was already spent.

With the wheel out of the latch, the keys own it alone. Hold rewind, let go, and the tape carries
on with exactly what it was doing before — which is what v1.2 promised and what the timer was
quietly undoing.

### The wheel press

Still LightControl's, not this app's. Its default binds the wheel click to the torch, and anything
that is not `PassThrough` consumes the key, so the press never reaches any app. That is fixed in
LightControl rather than here — see its own release — by treating BrightRecorder as an app that
owns the whole wheel, turns and press alike.

### Under the hood

83 tests, up from 74. Nine new ones cover the wheel, and the first of them is the reported fault
itself: a slow steady turn with notches 400 ms apart — well past the timeout that used to end the
wind — must never drop the tape to a standstill mid-turn. The others pin that faster turning moves
the tape faster, that a turn eases in rather than lurching, that it cannot run away past the
ceiling, and that letting go comes down smoothly rather than in one step.
