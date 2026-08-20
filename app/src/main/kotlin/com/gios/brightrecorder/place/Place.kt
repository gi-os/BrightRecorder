package com.gios.brightrecorder.place

import java.util.Locale

/**
 * How well the place is known, which is the difference between a name and a guess.
 *
 * It matters because a clip is filed the instant you stop recording, and a moment is four seconds
 * long. The lookup is very often still running — so a clip gets the best name available *then*,
 * and the name it gets has to say whether it is worth replacing when the real one turns up.
 */
enum class Fix {
    /** Nothing at all. Should never reach a filename; see [Places.best]. */
    None,

    /**
     * Right region, wrong detail: the network's country, which costs nothing to read and is right
     * within a country's width. See [Coarse] for why that is as far as this tier goes.
     */
    Coarse,

    /**
     * A real name, found earlier and remembered. Where the phone was, not necessarily where it is.
     *
     * Between the other two: far better than a country, and still worth replacing when a live
     * lookup lands, which is why it is a tier of its own rather than being filed as [Named].
     */
    Remembered,

    /** A named place from a real position: a street, a neighbourhood, a city. */
    Named,
}

data class Place(val name: String, val fix: Fix) {
    val known: Boolean get() = fix != Fix.None
}

/**
 * The place the phone knows it is in without asking anybody.
 *
 * The floor under a clip's name, for when there is no position or no way to look one up. It needs
 * no permission, no network request and no fix.
 *
 * ### Why the time zone is not used, having been
 *
 * The first version of this named the time zone's city — `Europe/Paris` is Paris — and it was wrong
 * in the one situation this app is for. A time zone is where the phone thinks it *lives*, and it
 * lags or never moves at all while travelling, so a phone set to `America/New_York` labelled every
 * recording "New York" wherever in the world it was. Worse, it did so with a city's precision,
 * which reads as a fact rather than as the guess it is.
 *
 * So what is left is the country, and the **network's** country first: that is where the phone is
 * standing, reported by whatever tower it is talking to, and it changes when you land. The locale's
 * country is behind it, for a phone with no SIM in it, and it is the same kind of guess the time
 * zone was — so it is a last resort rather than a first.
 *
 * A country is coarse, and deliberately so. It is honest at the precision it has, and a clip filed
 * under one is renamed the moment a real lookup lands — see `Places.best` and `Pending`.
 */
object Coarse {

    /**
     * [networkCountry] is the two-letter code the mobile network reports, or null without a SIM.
     * Supplied by the caller rather than read here so this stays free of Android; see
     * [Places.coarse].
     */
    fun place(
        locale: Locale = Locale.getDefault(),
        networkCountry: String? = null,
    ): Place {
        networkCountry?.let { code -> nameOfCountry(code, locale)?.let { return Place(it, Fix.Coarse) } }
        nameOfCountry(locale.country, locale)?.let { return Place(it, Fix.Coarse) }
        return Place("", Fix.None)
    }

    /** A two-letter country code as a name, in the phone's own language. */
    private fun nameOfCountry(code: String, locale: Locale): String? =
        code.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
            ?.let { runCatching { Locale.Builder().setRegion(it).build() }.getOrNull() }
            ?.getDisplayCountry(locale)
            // An unassigned code displays as the code itself, which is not a place name.
            ?.takeIf { it.isNotBlank() && it.length > 2 }
}
