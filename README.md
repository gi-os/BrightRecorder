# BrightRecorder

A tape recorder for the **Light Phone III**. Record a moment, and it becomes a clip
named for where you were and when. Wind through the whole lot with the brightness
wheel, forwards or backwards, at any speed — and hear it as you wind, the way a tape
machine lets you.

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

**Current release: v1.0.1** (tag `v1.0.1`).

## Install by hand

Grab the APK from the [latest release](../../releases/latest) and sideload it:

```bash
adb install -r BrightRecorder-v1.0.<run>.apk
```

Every push to `main` publishes a signed release, so `-r` upgrades in place.

One stable key signs every build (`keystore/brightrecorder.jks`, committed on purpose)
and exactly one APK ships per release, which is what Obtainium and the index need. The
certificate SHA-256 is pinned in `signing-fingerprint.txt` and CI fails on drift — a
changed certificate otherwise surfaces only as `Failure: Invalid` at install time.

## The tape

The idea the whole app rests on is that there are no separate files to play. Every clip
is butted against the next one in recording order, and the machine addresses all of them
as **one continuous tape** with a single position running from the first sample to the
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
| anything else | The wheel, shuttling by hand |

## The wheel

The LPIII's brightness wheel is a `Pixart pat9126ja` optical sensor that emits one key
event per notch, roughly every 35 ms while it is turning. It reports notches, not
position, and it says nothing about how fast it is being turned.

So speed is inferred from how thickly the notches arrive. Each one shoves the tape a
little harder in its direction and the shove bleeds away continuously — spin quickly and
the shoves arrive faster than they decay, ease off and it coasts down, stop and it settles
back to whatever the transport was doing. A hard spin reaches about 5x; an unhurried one
sits near 1.4x, which is the speed you want for finding a word.

The tape has mass, in other words, and that is deliberate. The obvious implementation —
one notch moves the tape *n* milliseconds — was the first one tried and it is unusable:
every notch becomes an instant jump, a jump in a waveform is a step, and a step is a
click. Driving *speed* means the read position always moves continuously, which is what a
tape head does.

`Shuttle` is that flywheel, and it is nine lines of arithmetic with no Android in it, so
how the wheel feels is unit-tested rather than only felt.

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

The location lookup runs **during** the recording, never before it. Pressing record starts
the microphone in the same frame; a fix takes anywhere from a second to never, and indoors
it is usually never. Whatever has been found by the time you stop is what the clip is
called, and a clip with no fix is filed under `Somewhere`, which is an honest label rather
than a guess. Coarse location only — the title says which part of town, not where you
stood.

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
| v1.0.1 | First release. |

## Licence

MIT. See [LICENSE](LICENSE).
