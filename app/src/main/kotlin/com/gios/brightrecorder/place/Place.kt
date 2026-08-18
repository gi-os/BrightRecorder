package com.gios.brightrecorder.place

import java.util.Locale
import java.util.TimeZone

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
     * Right region, wrong detail. From the phone's own settings rather than from a position: the
     * time zone and the network's country are both correct within a few hundred kilometres and
     * cost nothing to read, which is what makes them the right thing to fall back to.
     */
    Coarse,

    /** A named place from a real position: a street, a neighbourhood, a city. */
    Named,
}

data class Place(val name: String, val fix: Fix) {
    val known: Boolean get() = fix != Fix.None
}

/**
 * The place the phone knows it is in without asking anybody.
 *
 * There used to be nothing here, and a clip recorded before the lookup finished — which is most of
 * them — was filed under "Somewhere". "Somewhere" is honest and completely useless: a list of
 * fourteen clips called "Somewhere" is the filing system failing at the one job it has.
 *
 * None of these needs a permission, a network, or a fix:
 *
 *  - **The time zone**, which on a phone with a SIM tracks the network and names a city.
 *    `Europe/Paris` is a better guess for where you are than nothing is, and while travelling it
 *    is the first thing about the phone to change.
 *  - **The network's country**, when the zone is one of the ones that names no place — `UTC`,
 *    `GMT+02:00`, `Etc/GMT-3`. This is where the phone is, as opposed to where it is configured
 *    to think it is, so it comes ahead of the locale.
 *  - **The locale's country**, last, for a phone with no SIM in it.
 *
 * All are marked [Fix.Coarse] so a real name replaces them the moment one arrives.
 *
 * There is one way to get nothing out of this, and it is worth being straight about because the
 * promise elsewhere is that a clip is never called "Somewhere": a phone reporting `UTC` with no
 * SIM and a locale carrying no country has told us nothing about where it is, and inventing a
 * place for it would be a lie rather than a guess. Every phone this app runs on reports at least
 * one of the three.
 */
object Coarse {

    /**
     * [networkCountry] is the two-letter code the mobile network reports, or null without a SIM.
     * Supplied by the caller rather than read here so this stays free of Android; see
     * [Places.coarse].
     */
    fun place(
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        networkCountry: String? = null,
    ): Place {
        cityOf(zone.id)?.let { return Place(it, Fix.Coarse) }
        networkCountry?.let { code -> nameOfCountry(code, locale)?.let { return Place(it, Fix.Coarse) } }
        nameOfCountry(locale.country, locale)?.let { return Place(it, Fix.Coarse) }
        return Place("", Fix.None)
    }

    /**
     * The city out of a zone id: `Europe/Paris` is Paris, `America/New_York` is New York.
     *
     * Region-only and offset-only ids name no place, and neither do the legacy three-letter ones,
     * so anything without a `/` is refused outright. `Etc/GMT-3` has one and still names nothing,
     * hence the region check as well.
     */
    private fun cityOf(id: String): String? {
        val slash = id.lastIndexOf('/')
        if (slash <= 0) return null
        if (id.startsWith("Etc/")) return null
        val city = id.substring(slash + 1).replace('_', ' ').trim()
        if (city.isEmpty() || !city.all { it.isLetter() || it == ' ' || it == '-' }) return null
        return city
    }

    /** A two-letter country code as a name, in the phone's own language. */
    private fun nameOfCountry(code: String, locale: Locale): String? =
        code.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
            ?.let { runCatching { Locale.Builder().setRegion(it).build() }.getOrNull() }
            ?.getDisplayCountry(locale)
            // An unassigned code displays as the code itself, which is not a place name.
            ?.takeIf { it.isNotBlank() && it.length > 2 }
}
