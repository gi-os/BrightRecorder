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
- **There is a floor.** Failing all of that, the phone's time zone names a city and its locale
  names a country, neither of which needs a permission, a network or a position. `Europe/Paris` is
  a better guess for where you were than nothing is.

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
