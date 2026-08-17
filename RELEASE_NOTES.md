## BrightRecorder v1.3 — More than one tape

**A shelf of tapes instead of one endless one. Name each tape, mark it with a pattern, swipe
through them, and load the one you want to record onto.**

One tape for a trip. One for the flat. One for the year. They stay separate, they play
separately, and the machine only ever has one on it — which is the point, because a single tape
holding everything you have ever recorded is a tape you stop putting anything on.

### The shelf

A third screen, **SHELF**. One cassette at a time, swiped through — a pager rather than a list,
because what is being chosen is an object and picking one should feel like sliding the next into
view. The wheel moves along the shelf too, one tape per notch; it is the only screen where a
notch is a step rather than a speed, because there is nothing here to wind.

Swiping only *looks*. **LOAD** puts a tape on the machine, and that is a deliberate press so that
browsing the shelf while something is playing does not keep stopping the tape. **NEW** starts a
fresh one and loads it straight away, because you made it in order to record onto it.

### Patterns, since there is no colour

A shelf of cassettes is normally told apart by colour and this panel has none. So each tape
carries a **pattern** on its label instead — plain, stripes, checks, dots, grid, lean, waves,
chevron — which survives greyscale, survives daylight, and survives being seen out of the corner
of your eye. **MARK** cycles it.

They are deliberately coarse. A fine hatch and a fine dot are the same thing at 40dp on this
screen, so each differs in *rhythm* rather than texture. Eight of them: past that they start
rhyming, and a shelf bigger than eight wants reading by name anyway.

The cassette is drawn rather than an image — sharp at any size, no assets to ship — and its reels
fill the way the real ones do, so a tape with a lot on it looks different from a fresh one before
you have read a word of it. How full it looks is measured against the longest tape you have,
because there is no such thing as a full tape.

### A tape is a folder

No new machinery and no database. A tape is a directory of clips:

    tapes/2026-08-17 143205 Trip to Rome/2026-08-17 143912 Trastevere, Rome.wav

Filed exactly the way clips are — timestamp first so the shelf sorts by when each tape was
started, human name after. Renaming a tape renames the directory, which is atomic and moves
nothing. Copy the store onto a desktop and it reads as itself, with no index to export.

The pattern is a one-line file inside the folder, and it is the only thing in this app that lives
outside a filename. It has to be somewhere: derived from the name, a rename would silently
repaint the tape; put in the folder name, every pattern change would rewrite the tape's identity.
A folder without one still gets a stable pattern derived from its name, so a tape copied in from
elsewhere looks like something rather than nothing.

### Everything already recorded moves across

Clips used to live in one flat folder. They move onto the shelf on first launch, into a tape
called **Tape**, stamped with the earliest clip on it so it sorts as the oldest tape — which is
what it is.

The move is file-by-file `renameTo` inside the same filesystem: no stream, no temporary copy,
nothing that can half-finish. A file that will not move is left exactly where it is and the old
folder is kept, so a partial migration loses nothing and finishes itself on the next launch.
These recordings cannot be made again, so that is the one thing this release was careful about.

### Deleting

`Tapes.delete` refuses a tape with clips still on it, so no call in this app can destroy
recordings by accident — you empty a tape one clip at a time, through the same confirmation every
other delete goes through, and only then can the tape go. The last tape cannot go either: the
machine always has something on it.

### Also

The transport screen shows which tape is loaded, small and to the right — you only look at it
when you are about to record, but you have to be able to, or a recording lands on whichever tape
happened to be on and you find out weeks later. MOMENTS is titled with the tape it is listing.
Which tape was loaded is remembered across launches by folder name rather than by position, so
adding a tape does not change which one comes back.

### Under the hood

74 tests, up from 61. Thirteen new ones cover the shelf, and most are about not losing
recordings: that the migration moves every clip and keeps their order, that migrating twice does
nothing the second time, that renaming keeps a tape's creation stamp and brings its clips with
it, that renaming onto an existing tape is refused without destroying anything, and that a tape
with clips on it cannot be deleted.
