package com.gios.brightrecorder.place

import java.util.Locale

/**
 * A place name from OpenStreetMap, for a phone that has no geocoder of its own.
 *
 * ### Why this exists
 *
 * Android's `Geocoder` is not part of Android. It is a thin client for a service that Google Play
 * Services provides, and on a phone without Play Services — the Light Phone III is one —
 * `Geocoder.isPresent()` is false, every lookup answers nothing, and the chain in [Places] falls
 * through to its floor: the network's country. That is how every clip on the phone this app was
 * written for came to be called "United States". The position was found; there was simply nobody
 * to ask what it was called.
 *
 * Nominatim is OSM's own reverse geocoder. It needs no key and no account, only a `User-Agent`
 * that says who is asking and a request rate a person pressing a record key cannot exceed. The
 * naming below is the pure half — a map of address parts in, a name out — so it can be tested
 * without a phone; `Places.fetch` is the network half.
 *
 * ### Why it never names a street
 *
 * This app holds `ACCESS_COARSE_LOCATION` and nothing finer, and a coarse position is deliberately
 * wrong by up to two kilometres. A street name read off a position like that is some street two
 * kilometres from where you stood, written down with a street's confidence. A neighbourhood is the
 * right precision for the position we actually have: "Lower East Side, New York" is true of the
 * whole two kilometres, and it is also how a person says where they were.
 */
object Nominatim {

    /** The request, minus the scheme and host, for [latitude], [longitude] in the phone's language. */
    fun path(latitude: Double, longitude: Double, locale: Locale = Locale.getDefault()): String {
        val lang = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en"
        return "/reverse?format=jsonv2&zoom=$ZOOM&addressdetails=1" +
            "&lat=${"%.5f".format(Locale.US, latitude)}&lon=${"%.5f".format(Locale.US, longitude)}" +
            "&accept-language=$lang"
    }

    /**
     * "Somewhere, city" from Nominatim's `address` object, or null if it names nothing usable.
     *
     * The keys are OSM's, and OSM has a dozen words for a city depending on where in the world it
     * is: `city`, `town`, `village`, `municipality`. The same for a neighbourhood. Each list below
     * is ordered most specific first, and the first key present wins.
     */
    fun name(address: Map<String, String>): String? {
        fun first(keys: List<String>) = keys.firstNotNullOfOrNull { k ->
            address[k]?.trim()?.takeIf { it.isNotBlank() }
        }

        val city = first(CITY)?.let(::plain)
        val spot = first(SPOT)?.let(::plain)?.takeIf { !it.equals(city, ignoreCase = true) }
        val region = first(REGION)

        return when {
            spot != null && city != null -> "$spot, $city"
            city != null -> city
            spot != null -> spot
            // Nothing city-shaped at all, which happens at sea and in the desert. The state or the
            // country is coarse but it is a real answer, and a clip should never say "Somewhere"
            // when it could at least say the state.
            else -> region
        }?.takeIf { it.isNotBlank() }
    }

    /**
     * The name a person uses, not the administrative one.
     *
     * OSM's record for New York is "City of New York", and the same "City of" prefix sits on a
     * handful of other American cities. Nobody says it; a clip called "Lower East Side, City of
     * New York" reads like a form. The prefix is dropped and nothing else is touched.
     */
    private fun plain(name: String): String =
        CIVIC_PREFIXES.firstOrNull { name.startsWith(it, ignoreCase = true) }
            ?.let { name.substring(it.length).trim().ifBlank { name } }
            ?: name

    private val SPOT = listOf("neighbourhood", "quarter", "suburb", "city_district", "borough", "district", "hamlet")
    private val CITY = listOf("city", "town", "village", "municipality", "county")
    private val REGION = listOf("state", "region", "province", "country")

    private val CIVIC_PREFIXES = listOf("City of ", "Town of ", "Village of ")

    /**
     * Neighbourhood level. 16 would add the street, which for the reasons in the class comment
     * this app has no honest use for; 14 names only the suburb and loses the neighbourhood.
     */
    private const val ZOOM = 15

    const val HOST = "https://nominatim.openstreetmap.org"
}
