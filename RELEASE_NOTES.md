## BrightRecorder v1.16 — Letting go of fast-forward finally plays

**The reported fault, in its exact words: press play, fast-forward, let go — and it just stays
paused.** Five releases have chased this, and the readout added in v1.11 did its job: it said
`→ PLAY`, and the tape did not play, which put the fault downstream of every rule in the deck.

Here is where it was. A wind runs at 8x and a moment is a few seconds long, so fast-forwarding
while playing reaches the **end of the tape almost every time you use it**. The head parks against
the end under your thumb — correctly. You let go, and the resume rule does its job — correctly:
the transport says playing. Then the very first audio block, 23 milliseconds later, finds the head
at the end of a moving tape, reports it, and the transport stops. Play "resumed" for zero audible
samples. v1.11 looked straight at this and called it right — "a start that instantly stops again
is the correct thing for a tape that has run out" — and that judgment, not any race or lost
release, was the bug. The rule was never broken again after v1.7; it was being obeyed into
silence.

The play key has guarded against this exact dead button all along: pressing play with the head at
the very end moves it to the start first, because a start that instantly stops "reads as a dead
button." Letting go of a wind now gets the same answer — resume into play with the head parked at
the end, and the head goes back to the start of the tape. Rewind-to-the-front already behaved:
letting go plays on from the beginning. Both directions now end in the same place: sound.

The rule in the deck is untouched and still has no exceptions — letting go goes back to what the
tape was doing. Where the head sits was never the rule's business; it is the controller's, and the
controller now answers it the way it always answered the play key.

- 216 tests.

---

## BrightRecorder v1.15 — The double tap actually skips

**v1.14 said a second tap of a wind key skips a moment. It did not — the notes shipped and the
wiring did not.** The deck grew `cancelWind` and the tests for it, but the controller was never
touched: a second tap still ran the old 16x gear from v1.11, and nothing anywhere called the skip.
So double-tapping fast-forward wound at 16x instead of jumping, which on a tape of short moments
reads as the machine ignoring you — and looks exactly like the resume rule being broken again,
which it was not.

The wiring is in now, and it is the controller's four lines to own: a press that lands within the
double-tap window of the *same* key's last release is a skip, not a wind. The head jumps — forward
a moment on fast-forward, back on rewind — and the transport is not touched at all, because the
first tap's wind already ended at its own release and put the tape back to whatever it was doing.
Double-tap while playing and you land on the next moment still playing; the rule letting go obeys
is the rule skipping obeys, exactly as v1.14 promised.

Two smaller things fell out of doing it properly:

- **The gears are actually gone.** `Deck` still carried the 16x/32x table and the step logic the
  v1.14 notes said were removed. Winding is one speed, 8x, and the code now agrees with the notes.
- **Tap-tap-tap hops a moment per tap.** The release after a skip found nothing winding and
  returned before marking its time, so a third tap was measured against the wrong release and
  started a wind instead of skipping again. The release marks time first now, whatever else it
  finds to do.

Fixes the mess reported today — double-tapping a wind key wound instead of skipping, in every
build of v1.14.

- 216 tests.

---

## BrightRecorder v1.14 — Skip a moment, and rename one

### Renaming was unreachable, and so was deleting

Holding a moment in the list did nothing at all. The row carries its own tap handler, and a row that
handles its own taps **consumes the pointer events** — so the hold I had put on the container around
it never saw them. Renaming shipped in v1.12 behind a gesture that could not fire, and the
long-press delete that predates it had never worked either.

The gesture lives on the row itself now. Hold a moment and the sheet opens: rename it, send it to
BrightChat, or delete it.

### Double-tap a wind key to skip a moment

Winding is for finding a place *inside* a recording. A tape of moments is also a list, and getting
to the next one by winding through the rest of this one is the long way round. So a second tap of
fast-forward jumps to the next moment, and a second tap of rewind jumps to the start of the previous
one.

It obeys the same rule letting go does: the tape goes back to whatever it was doing. Double-tap
while playing and you land on the next moment still playing.

**This replaces the 16x and 32x gears** from v1.11. That was the other thing a second tap could
mean, and skipping is worth more: a wind at 8x is fast enough to cross a moment in a second or two,
and past roughly 8x speech stops being something you can navigate by — which is the entire point of
hearing the tape while it winds. Winding is one speed again.

- 216 tests.

---

## BrightRecorder v1.13 — Send a moment

**A moment can be sent to BrightChat.** Hold a moment in the list to open it and there is a SEND
key beside the name.

A recording lives in this app's private storage, so a share cannot pass a file path — the receiving
app has no permission to read it and the path resolves to nothing. A `FileProvider` issues a
`content://` URI instead and the read grant rides on the intent: one clip at a time, read only,
revoked when the receiver is done with it. The paths it will serve are listed rather than being the
whole directory, because the same private folder holds the crash log and the report token.

With BrightChat installed the clip goes **straight there** — it declares itself a share target for
audio now, so an explicit intent opens it on a conversation with the moment attached rather than
putting a chooser in the way every time. Without it, you get a chooser, which is the right answer
for anything else on the phone that can take a sound.

The clip's own title travels with it, so a moment arriving somewhere that has never heard of a tape
still says where and when it was.

**BrightChat 2.x is needed for the direct hand-off** — see its release for the other half: sending
audio clips, and a player with a scrubber to listen to them on.

- 215 tests.

---

## BrightRecorder v1.12 — Recording, and where you actually were

### The reels turn while you record

They did not, and the reason is worth writing down: the engine publishes a tape speed of zero while
the microphone owns the audio path — correctly, because nothing is being *played* — and the reels
were reading that as a stopped machine. So the one moment you most want to see the tape moving was
the only moment it sat still. Recording moves the tape forward at exactly 1x, and the reels now say
so. The right one fills as the recording grows, too.

### The screen stays on while you record

The foreground service already held a partial wake lock, which keeps the CPU going so a recording
survives the panel going dark. This is the other half: while you are recording you are holding the
phone at something that is happening, watching the counter and the level, and having the screen go
out under your thumb means waking it to find out whether the recording is still running.

A flag on the window rather than a wake lock, so it needs no permission and the system takes it back
by itself — there is no path where this is left on with nothing recording.

### It said New York wherever you were, and that was my doing

Every clip named "New York" was the coarse fallback I added in v1.5, and it was wrong in the one
situation this app exists for. It named the **time zone's city** — and a time zone is where the phone
thinks it *lives*. It lags or never moves at all while travelling, so a phone set to
`America/New_York` labelled every recording New York wherever in the world it was. With a city's
precision, which reads as a fact rather than as the guess it was.

Three changes, in order of how much they matter:

- **The time zone is not used at all any more.** What is left is the country, and the **network's**
  country first — that is where the phone is standing, reported by the tower it is talking to, and
  it changes when you land.
- **The last real name is remembered** across launches, and beats any country. A phone that
  geocoded in Paris this morning and is indoors with no signal this afternoon says Paris, not
  France. This is the tier that gives back the specificity.
- **A clip recorded with no signal gets its real name later.** A recording needs no network; turning
  a position into a name does. So the position is kept beside the tape, and the next time you open
  the app with signal the clip is looked up and renamed — hours later if that is when it happens.

### Rename a moment

Hold a moment in the list to open it, and the place is editable. The place in a filename was only
ever a guess about where you were, and sometimes the useful name is not a street at all — "Ada's
first word" beats "Rue de Lappe, Paris" for a moment, and only you know which.

The **time** is not editable, deliberately: the timestamp is what puts the tape in order, and a tape
that reordered itself because you renamed something would be a different kind of object. A name you
typed also cancels any pending lookup for that clip — your name is not a guess for the geocoder to
overwrite.

Holding a moment used to be *delete*, which put the one irreversible action in the app behind the
easiest gesture to make by accident. Delete is a key on the sheet now, still behind its own
confirmation.

- 215 tests, up from 208.

### Still to come

Exporting a moment to BrightChat, which needs BrightChat to carry audio first: sending clips, and a
player with a scrubber. Then Whisper transcription. Those are the next rounds.

---

## BrightRecorder v1.11 — Letting go, with no exceptions

**The rule is: the tape goes back to whatever it was doing before you pressed the key. It took four
releases to get right, and the reason is that every attempt kept an exception to it.**

Each exception looked reasonable on its own, and each one fired far more often than the rule did:

- Reaching the **front** of the tape cancelled the wind *and its resume together*, so letting go left
  the tape stopped at zero. Fixed in v1.5.
- Reaching the **back** still cancelled it, on the reasoning that there is nothing left to play.
- And letting go **at the very end** refused to resume into play at all, to avoid a start that
  instantly stopped again.

Those last two are gone now. There is nothing left to play at the end of a tape, and the tape stops
on its own a moment later when it gets there — that did not need pre-empting, and pre-empting it is
what threw the resume away. A start that instantly stops again is the correct thing for a tape that
has run out.

Why this mattered so much in practice: a wind runs at 8x and a moment is a few seconds long, so a
wind reaches an end of the tape *almost every time you use it*. The exceptions were the normal case
and the rule was the rare one.

So both ends now do the same thing — park the reels and touch nothing else. The key may still be
down, and letting go is the only thing allowed to decide where the tape goes.

### The machine says what letting go will do

While a wind key is held, the readout shows where it is going: `<< 8.0x → PLAY`. Four releases of
this not working were four releases of guessing, and the machine already knows the answer.

It is also the one thing that tells the two possible faults apart. If it says **→ PLAY** and the
tape then does not play, the fault is downstream of every rule in the transport — and that is worth
one word from you rather than another round of me guessing.

### Winding gears: 8x, and 16x and 32x on a second tap

Winding now runs at **8x** rather than 4x. 4x is slower than you want for finding a moment three
clips back.

**Tap the same key again and it steps up** — 8, then 16, then 32. Past roughly 8x speech stops being
something you can navigate by, so the higher gears are a deliberate second and third press rather
than where the key starts. A press that is not a second tap starts again at 8x, and the gear is per
key, so tapping rewind does not inherit fast-forward's.

### The wheel holds its top speed

Spun hard, the wheel's measured notch interval jitters around the sensor's floor, so the rate hunted
a few tenths below 8x and the readout never settled on it — which reads as the wheel not holding its
top speed. Anything asking for the ceiling or beyond now gets exactly the ceiling, and the ramp sits
on 8.0x instead of approaching it for ever.

- 208 tests, up from 198.

---

## BrightRecorder v1.10 — Grey ink

**The pen now draws in grey as well as black and white.**

There is no grey on this panel, so a grey line is a *pattern* — the halftone at half, in the same
Bayer matrix and at the same cell size the photographs use. That matters more than it sounds: a
grey stroke laid over a halftoned picture has to be made of the same black and white the picture is,
or it reads as a different material sitting on top of one rather than as ink on a photograph.

It is also the useful ink over a photograph. A halftone has as much white in it as black, so a white
line vanishes into its light half and a black one into its dark half. Grey reads on both.

The ink key cycles white → grey → black. Rubbing out stays a mode of its own rather than becoming a
fourth colour, because it does something different in kind: it removes what it passes over so the
photograph shows through, where black ink would blot the photograph out.

### One paint, so the preview is a preview

The strokes you see while drawing and the strokes written into the label were being drawn by two
different code paths — Compose for the preview, `android.graphics` for the save. They agreed about
solid colours by luck, and had no reason whatever to agree about a pattern. Both now go through the
same paint, so grey looks the same in your hand as it does on the tape, and the cell is scaled to
the canvas so it is not coarser in the preview than on the shelf either.

- 198 tests, up from 195.

---

## BrightRecorder v1.9 — The label, properly

**Four things reported against v1.8, and the first is why the others mattered.**

### The label in the editor is the label on the tape

They were different shapes. The editor composed a 2.5:1 canvas; the cassette's label window was
whatever fell out of the size the drawing happened to be given, about 4:1. So a photograph filled
the editor and then sat letterboxed in the middle of the label on the shelf — which is exactly the
"images do not fill the entire label" that was reported.

The stored label is now the window's own shape, and the window is *derived from it* rather than
chosen separately — one set of numbers, in one place, with a test that fails if the two ever drift
apart again. The cassette is drawn at a fixed aspect for the same reason: give it any other
proportions and the label stops fitting. A picture fills its label exactly now, with no crop and no
letterbox.

### Move the photograph, and grade it

Drag it with a finger; pinch to zoom into it. Six grades on a key that cycles: plain, bright, dark,
punch, soft and invert. A dark room halftones to almost solid black and a bright sky to almost solid
white, so on a two-colour panel the grade is often the difference between a picture and a smudge —
and invert is frequently the more legible of the two.

That needed the picked photograph to be **kept**, which it was not: the first version halftoned it
at pick time and threw the original away, so every later decision would have been destructive —
nudging it would have halftoned an already-halftoned image, and a grade would have had nothing to
work from. The source is now stored beside the label and everything is rendered from it, so moving
and grading stay reversible for as long as the tape exists.

The nudge is clamped to what the picture has to give: pushed to its limit an edge lands exactly on
the edge of the label, so it is not possible to shove a photograph off its own label.

### Move the title, turn it, and set it in something with character

Drag it anywhere on the label; pinch to size it and turn it. STRAIGHT puts it back.

Four new faces: **cursive**, **comic**, **hand** and **pixel**, alongside plain, serif, typed,
spaced and heavy. Comic Sans is not on Android and never has been — `casual` is Coming Soon, which
is the same idea done better — and `cursive` is properly joined up. **Pixel** has no typeface at all
because Android ships no bitmap font: it is set small and blown back up with no filtering, so every
glyph becomes blocks. That is what a bitmap font is, and it is what this panel renders best.

### Draw in black as well as white

A key swaps the ink. Black is what you want over the light half of a halftone, white over the dark
half, and the eraser is a third thing again — it *removes* what it passes over rather than painting
black, because the drawing sits above the photograph and black ink would blot the photograph out
instead of revealing it.

### One row of keys could not hold all of that

So there are three tools — DRAW, PHOTO, TEXT — and both the keys and the meaning of a finger on the
label follow the one in hand. Without that the bottom of the screen would be eleven keys wide and a
drag would have to guess whether you meant to draw, move a picture or move a title.

### Under the hood

- `LabelSpec` — where everything sits, which face, which grade, as numbers rather than pixels. That
  is what makes all of it reversible.
- `label-source.jpg` beside the label, so a photograph can be moved after it is chosen.
- A label written by v1.8 still opens; its two-line file is read and replaced.
- 195 tests, up from 182.

---

## BrightRecorder v1.8 — Labels

**A cassette you have to play to identify is the problem the shelf was supposed to solve. Now you
write on it.**

### Draw on the label

A finger draws; **RUB** rubs out; **UNDO** takes back the last stroke. Strokes are kept as strokes
rather than as bitmaps, which is what makes undo a single step instead of a megabyte of history per
mark, and they are only flattened into the label when you save.

The rub-out genuinely removes what it passes over rather than painting black on top — it has to,
because the drawing sits over the photograph and black ink would blot the photograph out instead of
revealing it.

### Put a photograph on it

**PHOTO** opens a picker over the phone's own camera roll. Not the system one: that reads
MediaStore, and nothing on LightOS keeps MediaStore current, so a photograph taken minutes ago is
simply not offered. This walks DCIM and Pictures directly, the way BrightChat does, and a picture is
visible the moment it is written.

**STARRED** narrows the grid to the photographs you starred in Roll. A star is the one fact about a
picture that only Roll knows — `IS_FAVORITE` exists in MediaStore but is writable in practice only
by the system gallery — so Roll offers its list through a read-only provider and this reads it. The
key appears only when Roll is installed and has something to say; on a roll a few hundred pictures
deep it is the difference between finding the photograph you want and scrolling for it.

The picture is reduced to the two colours this panel has by an ordered Bayer halftone, once, when
you choose it. Letting the display do that conversion produces a grey smear; doing it deliberately
produces something that looks printed, which is what belongs on a cassette.

### Set the title on it, in a face you choose

**TYPE** puts the tape's name on the label and walks through five faces — plain, serif, typed,
spaced and heavy — coming round to off again. Five rather than fifty because on a panel this size
the difference between two similar grotesques is invisible, while the difference between a plain
face, a serif, a typewriter and spaced-out capitals is the whole character of the label.

The title stays **live**: it is stored as a choice, not burned into the drawing. Rename the tape and
the label follows. Change your mind about the face and nothing has to be rubbed out.

### The shelf shows all of it

Every cassette on the shelf is drawn with its own label — photograph, handwriting and title —
instead of the abstract pattern, which now only stands in for a tape nobody has labelled yet. That
is what the shelf was for: picking a tape by recognising it rather than by reading a list. **LOAD**
puts the one you are looking at on the machine, as before. The pattern moved to the naming sheet,
which is where you are when you are deciding what a tape is.

### Where it all lives

In the tape's own folder, beside the recordings: `label-photo.png`, `label-drawing.png` and a
one-line `label-title.txt`. Same rule as everything else here — a tape is a directory, and there is
no index anywhere that could disagree with it. Copy the folder off the phone and the label goes with
it. Each image is written to a temporary name and renamed into place, so a process killed mid-save
leaves the label you had rather than half of a new one.

- 182 tests, up from 172.

---

## BrightRecorder v1.7 — Letting go of a wind key, for the last time

**Reported four times: rewind or fast-forward while the tape is playing, let go, and it does not
carry on playing.**

Each previous attempt found a real bug and fixed it. None of them found this one, because it was
not in the rules — it was in there being two copies of them.

### One transport, not two

The deck decides what the machine is doing. The engine needs to know, and it kept its own copy,
written from five places in the controller — one of which is the **audio thread**, reporting that
the head has run out of tape.

Letting go at the end of a wind is exactly when those two collide. The head reaches the front of
the tape and the audio thread starts writing "stopped" into its copy; the finger comes up a
millisecond later and the deck says "playing"; and whichever wrote last won. When the audio thread
won, the tape stopped and stayed stopped, and nothing afterwards ever corrected it — the controller
had already done its part, and the ticker faithfully reported what the engine believed.

It could not be caught by testing the rules, because the rules were right. The engine now reads the
transport from the deck instead of holding a copy, so there is nothing to go stale and the whole
class of race is gone rather than narrowed. Two new tests pin it from both directions: the end of
the tape reported *before* the release and *after* it must both leave the tape playing.

### And the release cannot be lost

Everything about a momentary key rests on the release actually happening, and if it does not the
tape simply winds to the end of its travel and stops — which is indistinguishable, from the outside,
from the resume logic being wrong. That ambiguity is most of why this took four goes.

So the wind keys are rewritten against the raw pointer events, and the release is issued from a
`finally`. A press ends when the finger lifts, and it also ends if the key leaves the screen, the
window loses its pointers, or the app goes away under a held thumb. There is now no path through
that code where a wind is started and not ended.

Sliding your thumb off the key no longer ends the wind, which is a small deliberate change: it is a
physical control under a thumb, and lifting is the only thing that means let go.

- 172 tests, up from 169.

---

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
