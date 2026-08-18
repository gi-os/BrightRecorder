package com.gios.brightrecorder.label

import java.io.File

/** How a photograph is filtered on its way onto a label. */
enum class LabelFilter(val label: String) {
    /** As it came off the camera. */
    Normal("PLAIN"),

    /** Lifted, for a picture taken indoors that halftones to almost solid black. */
    Bright("BRIGHT"),

    /** Pulled down, for a bright sky that halftones to almost solid white. */
    Dark("DARK"),

    /** Pushed apart, which on a two-colour panel is what makes a subject read at label size. */
    Punch("PUNCH"),

    /** Flat and even, for a picture with one very bright thing in it. */
    Soft("SOFT"),

    /** White on black. A negative is often the more legible of the two at this size. */
    Invert("INVERT"),
    ;

    fun next(): LabelFilter = entries[(ordinal + 1) % entries.size]

    /** Contrast about mid-grey, then brightness, both in the 0..255 domain. */
    fun apply(gray: Int): Int {
        val contrasted = when (this) {
            Punch -> 128 + ((gray - 128) * 175) / 100
            Soft -> 128 + ((gray - 128) * 62) / 100
            else -> gray
        }
        val shifted = when (this) {
            Bright -> contrasted + 45
            Dark -> contrasted - 45
            else -> contrasted
        }
        val clamped = shifted.coerceIn(0, 255)
        return if (this == Invert) 255 - clamped else clamped
    }
}

/**
 * Everything about a label except the pixels: where things sit, how big, which face, which filter.
 *
 * One short text file in the tape's folder, `label.txt`, beside the images. Keeping the *placement*
 * out of the images is what makes a label editable rather than a one-shot: a photograph can be
 * moved after it has been chosen, a title can be nudged and turned without being rubbed out and
 * written again, and changing a filter re-renders from the picture you picked rather than from the
 * halftoned copy of it.
 *
 * Written as `key=value` lines. Unknown keys are ignored and missing ones take their defaults, so a
 * label written by a later version opens here without losing what this version does understand —
 * which matters because a tape folder is meant to survive being carried between installs.
 */
data class LabelSpec(
    // ------------------------------------------------------------------ the title
    val titleShown: Boolean = false,
    val font: LabelFont = LabelFont.Plain,
    /** Where the title sits, as a fraction of the label. 0.5, 0.5 is the middle. */
    val titleX: Float = 0.5f,
    val titleY: Float = 0.82f,
    /** Cap height as a fraction of the label's height. */
    val titleSize: Float = 0.26f,
    /** Degrees, clockwise. */
    val titleAngle: Float = 0f,

    // ------------------------------------------------------------- the photograph
    val filter: LabelFilter = LabelFilter.Normal,
    /** How much of the label the picture covers. 1 is "just fills it". */
    val photoScale: Float = 1f,
    /** How far the picture is nudged, as a fraction of the label. */
    val photoX: Float = 0f,
    val photoY: Float = 0f,
) {
    fun withTitleAt(x: Float, y: Float) = copy(
        titleX = x.coerceIn(0f, 1f),
        titleY = y.coerceIn(0f, 1f),
    )

    fun withPhotoAt(x: Float, y: Float) = copy(
        photoX = x.coerceIn(-1f, 1f),
        photoY = y.coerceIn(-1f, 1f),
    )

    fun withPhotoScale(scale: Float) = copy(photoScale = scale.coerceIn(MIN_SCALE, MAX_SCALE))

    fun withTitleSize(size: Float) = copy(titleSize = size.coerceIn(MIN_TITLE, MAX_TITLE))

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val MIN_TITLE = 0.08f
        const val MAX_TITLE = 0.60f

        private const val FILE = "label.txt"

        /** The two-line file v1.8 wrote, still read so a label made then is not lost. */
        private const val LEGACY = "label-title.txt"

        fun read(tapeDir: File): LabelSpec = runCatching {
            val file = File(tapeDir, FILE)
            if (!file.isFile) return readLegacy(tapeDir)
            val values = file.readLines()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.take(at).trim() to line.drop(at + 1).trim()
                }
                .toMap()
            fun num(key: String, fallback: Float) = values[key]?.toFloatOrNull() ?: fallback
            LabelSpec(
                titleShown = values["title"] == "1",
                font = values["font"]?.let { name -> LabelFont.entries.firstOrNull { it.name == name } }
                    ?: LabelFont.Plain,
                titleX = num("titleX", 0.5f),
                titleY = num("titleY", 0.82f),
                titleSize = num("titleSize", 0.26f),
                titleAngle = num("titleAngle", 0f),
                filter = values["filter"]?.let { name -> LabelFilter.entries.firstOrNull { it.name == name } }
                    ?: LabelFilter.Normal,
                photoScale = num("photoScale", 1f).coerceIn(MIN_SCALE, MAX_SCALE),
                photoX = num("photoX", 0f),
                photoY = num("photoY", 0f),
            )
        }.getOrDefault(LabelSpec())

        private fun readLegacy(tapeDir: File): LabelSpec = runCatching {
            val file = File(tapeDir, LEGACY)
            if (!file.isFile) return LabelSpec()
            val lines = file.readLines()
            LabelSpec(
                titleShown = lines.getOrNull(0)?.trim() == "1",
                font = lines.getOrNull(1)?.trim()
                    ?.let { name -> LabelFont.entries.firstOrNull { it.name == name } }
                    ?: LabelFont.Plain,
            )
        }.getOrDefault(LabelSpec())

        fun write(tapeDir: File, spec: LabelSpec): Boolean = runCatching {
            File(tapeDir, LEGACY).delete()
            val file = File(tapeDir, FILE)
            if (spec == LabelSpec()) {
                // Nothing worth storing. A folder of recordings should not accrue a file that says
                // "everything is as it would have been anyway".
                file.delete()
                return true
            }
            file.writeText(
                buildString {
                    appendLine("title=${if (spec.titleShown) 1 else 0}")
                    appendLine("font=${spec.font.name}")
                    appendLine("titleX=${spec.titleX}")
                    appendLine("titleY=${spec.titleY}")
                    appendLine("titleSize=${spec.titleSize}")
                    appendLine("titleAngle=${spec.titleAngle}")
                    appendLine("filter=${spec.filter.name}")
                    appendLine("photoScale=${spec.photoScale}")
                    appendLine("photoX=${spec.photoX}")
                    appendLine("photoY=${spec.photoY}")
                },
            )
            true
        }.getOrDefault(false)

        fun clear(tapeDir: File) {
            runCatching { File(tapeDir, FILE).delete() }
            runCatching { File(tapeDir, LEGACY).delete() }
        }
    }
}
