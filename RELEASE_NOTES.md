## BrightRecorder v1.0 — A tape recorder for moments

**A recorder that files what you record by where you were, and lets you wind through the whole
lot with the brightness wheel — forwards, backwards, at any speed, hearing it as you go.**

It is not a voice recorder. There is no transcription, no trimming and no file manager. It
records rooms, streets, weather, rain on a window, and the only thing it does with them
afterwards is let you find them again.

### One tape, not a folder of files

Press record, press stop, and you get a clip named for where you were and when:

    Bastille, Paris at 17 Aug 2026, 14:32

The clips are then butted end to end in recording order and the machine treats all of them as a
single continuous tape, addressed by one position that runs from the first sample to the last.

That is the whole design, and everything else falls out of it. Playing to the end of a clip runs
straight into the next one — no gap, no track change, no next button, because the end of a clip
is just a position like any other. Winding back past the start of a clip lands in the one before
it. A position is a sample number, so scrubbing is arithmetic rather than a seek.

### The wheel has mass

The brightness wheel shuttles the tape. It is an optical sensor that reports one notch at a time
and says nothing about how fast it is being turned, so speed is inferred from how thickly the
notches arrive: each one shoves the tape a little harder and the shove bleeds away continuously.
Spin quickly and it winds fast. Ease off and it coasts down. Stop and it settles back to whatever
the transport was doing.

The obvious version — one notch moves the tape *n* milliseconds — was tried first and it is
unusable. Every notch becomes an instant jump, a jump in a waveform is a step, and a step is a
click, so a fast spin is a stutter of discontinuities rather than a sweep. Driving speed instead
of position means the head always moves continuously, which is what a tape head does.

Rewind and fast-forward are the same mechanism at 4x, with the audio audible. A real transport
lifted the tape off the head and gave you a mechanical whirr; here the point of winding is to
hear where you are, and past roughly 5x speech becomes chatter you cannot navigate by.

### Winding is dull, not shrill

Playing faster than 1x means skipping samples, and skipping samples is aliasing — content above a
quarter of the sample rate folds back down as a metallic whistle that is not in the recording.

So instead of picking one sample in four, the engine averages across the ones it crosses. A moving
average is a low-pass filter and this puts it exactly where the aliasing is created. It is also
what the physical object did: a tape head reads a finite length of tape at once, so winding fast
came out dull rather than sharp. The honest emulation and the correct signal processing are the
same operation.

### Where you were

The location lookup runs during the recording, never before it. Pressing record starts the
microphone in the same frame — a fix takes anywhere from a second to never, and indoors it is
usually never — so whatever has been found by the time you stop is what the clip is called.

Coarse location only: the title says which part of town you were in, not where you stood. A clip
with no fix is filed under `Somewhere`, which is an honest label rather than a guess.

### A recording cannot be lost

Samples go to disk as they arrive, and the WAV length field is patched when recording stops. If
the app dies mid-recording the length is wrong but every sample is already there, so the
temporary filename carries the start time and the next launch rebuilds the header from the file
size and files the clip properly. Only the place name is lost, because it died with the process.

Recordings are 22050 Hz 16-bit mono WAV, uncompressed — not for fidelity but because a tape you
can spin has to be addressable by sample, and compressed audio only decodes forwards from a
keyframe. It costs 2.6 MB a minute.

### Also in this release

Shake the phone twice and a sheet comes up to file a bug report, carrying the screen you were on,
the build, free space, heap and the last stack trace. The app reports its own noticed failures
too, which matters more here than elsewhere: a microphone that never opened looks exactly like a
microphone recording an empty room.
