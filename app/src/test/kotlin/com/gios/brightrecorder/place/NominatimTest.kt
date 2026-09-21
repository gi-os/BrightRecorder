package com.gios.brightrecorder.place

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second geocoder, for the phone that has none.
 *
 * Every clip on the Light Phone was called "United States" because Android's `Geocoder` is a
 * client for a Google service that phone does not have. These are the naming rules for the answer
 * OSM gives instead, fed the address maps Nominatim really returns.
 */
class NominatimTest {

    @Test
    fun `neighbourhood then city, and New York is not the City of New York`() {
        val address = mapOf(
            "road" to "Orchard Street",
            "neighbourhood" to "Lower East Side",
            "suburb" to "Manhattan",
            "city" to "City of New York",
            "state" to "New York",
            "country" to "United States",
        )
        assertEquals("Lower East Side, New York", Nominatim.name(address))
    }

    /** A coarse position is two kilometres wide, and a street is not something to say about it. */
    @Test
    fun `the street is never used`() {
        val address = mapOf("road" to "Rue de Lappe", "city" to "Paris", "country" to "France")
        assertEquals("Paris", Nominatim.name(address))
    }

    @Test
    fun `a town or a village is a city too`() {
        assertEquals("Trastevere, Rome", Nominatim.name(mapOf("quarter" to "Trastevere", "city" to "Rome")))
        assertEquals("Woodstock", Nominatim.name(mapOf("town" to "Woodstock", "state" to "New York")))
        assertEquals("Giverny", Nominatim.name(mapOf("village" to "Giverny", "country" to "France")))
    }

    @Test
    fun `a spot that is the city is not said twice`() {
        assertEquals("Paris", Nominatim.name(mapOf("suburb" to "Paris", "city" to "Paris")))
    }

    @Test
    fun `nothing city-shaped falls to the state, then the country`() {
        assertEquals("Nevada", Nominatim.name(mapOf("state" to "Nevada", "country" to "United States")))
        assertEquals("United States", Nominatim.name(mapOf("country" to "United States")))
        assertNull(Nominatim.name(emptyMap()))
        assertNull(Nominatim.name(mapOf("city" to "  ")))
    }

    @Test
    fun `the request asks for the phone's language and a neighbourhood's zoom`() {
        val path = Nominatim.path(40.7182, -73.9889, Locale.FRANCE)
        assertTrue(path, path.startsWith("/reverse?format=jsonv2&zoom=15"))
        assertTrue(path, "lat=40.71820" in path && "lon=-73.98890" in path)
        assertTrue(path, path.endsWith("accept-language=fr-FR"))
    }

    /** A comma decimal separator in the URL would be a different, wrong request. */
    @Test
    fun `coordinates are written with a full stop whatever the locale`() {
        val path = Nominatim.path(48.8566, 2.3522, Locale.GERMANY)
        assertTrue(path, "lat=48.85660&lon=2.35220" in path)
    }
}
