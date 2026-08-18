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
