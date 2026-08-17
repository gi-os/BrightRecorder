package com.gios.brightrecorder.tape

/**
 * How one tape is told apart from another at a glance.
 *
 * Colour is what a shelf of cassettes normally uses and this panel has none — it is greyscale on
 * a matte screen, and even the greys are unreliable in daylight. So the label is *patterned*
 * instead, which survives the panel, survives bright sun, and survives being seen out of the
 * corner of your eye while the phone is on a table.
 *
 * They are deliberately coarse. A fine hatch and a fine dot look identical at 40dp on this
 * screen, so every one of these is legible by its rhythm rather than by its texture: bands,
 * squares, spots, lines that lean. Eight of them, because a shelf of more than eight tapes wants
 * reading by name anyway, and past eight the patterns start rhyming.
 */
enum class Pattern {
    Plain,
    Stripes,
    Checks,
    Dots,
    Grid,
    Lean,
    Waves,
    Chevron,
    ;

    val label: String
        get() = when (this) {
            Plain -> "PLAIN"
            Stripes -> "STRIPES"
            Checks -> "CHECKS"
            Dots -> "DOTS"
            Grid -> "GRID"
            Lean -> "LEAN"
            Waves -> "WAVES"
            Chevron -> "CHEVRON"
        }

    /** The next one round, for a control that cycles rather than opening a picker. */
    fun next(): Pattern = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * A pattern for a tape that has never been given one.
         *
         * Derived from the name rather than random, so it is *stable*: the same tape gets the
         * same pattern on every launch, and a tape folder copied onto another phone looks the
         * same there. Random would mean a shelf that rearranged itself visually every time the
         * app was opened, which is worse than no pattern at all.
         *
         * Only reached for tapes that arrived from outside — a folder copied in, or one made by a
         * version before patterns existed. Anything created in the app is given a pattern and has
         * it written down.
         */
        fun forName(name: String): Pattern {
            // String.hashCode is stable across runs and platforms for the same characters, which
            // is the property being relied on here; `hashCode` on other types is not.
            val h = name.hashCode()
            return entries[((h % entries.size) + entries.size) % entries.size]
        }

        fun parse(raw: String?): Pattern? = entries.firstOrNull { it.name == raw }
    }
}
