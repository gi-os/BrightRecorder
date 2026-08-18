package com.gios.brightrecorder.place

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor under the place name.
 *
 * These are the answers a clip gets when there is no position and no geocoder — which, on a phone
 * indoors with no data, is most of them. Before this there was no floor and they were all called
 * "Somewhere".
 */
class CoarseTest {

    private fun place(zone: String, locale: Locale = Locale.US) =
        Coarse.place(TimeZone.getTimeZone(zone), locale)

    @Test
    fun `a zone names its city`() {
        assertEquals("Paris", place("Europe/Paris").name)
        assertEquals("Berlin", place("Europe/Berlin").name)
    }

    @Test
    fun `underscores in a zone id are spaces in a city`() {
        assertEquals("New York", place("America/New_York").name)
        assertEquals("Los Angeles", place("America/Los_Angeles").name)
    }

    @Test
    fun `a three-part zone id still names its last part`() {
        assertEquals("Buenos Aires", place("America/Argentina/Buenos_Aires").name)
    }

    @Test
    fun `a coarse place says it is coarse, so a real name can replace it`() {
        assertEquals(Fix.Coarse, place("Europe/Paris").fix)
    }

    /** Offset and region-only zones name no place, so the country has to answer instead. */
    @Test
    fun `an offset zone falls through to the country`() {
        assertEquals("France", place("GMT+02:00", Locale.FRANCE).name)
        assertEquals("United States", place("UTC", Locale.US).name)
    }

    @Test
    fun `an Etc zone names no place`() {
        assertEquals("Deutschland", place("Etc/GMT-3", Locale.GERMANY).name)
    }

    /**
     * The country is named in the phone's own language, which is the point of taking it from the
     * locale: a German phone files a clip under Deutschland, an English one under Germany.
     */
    @Test
    fun `the country is named in the phone's own language`() {
        assertEquals("Deutschland", place("UTC", Locale.GERMANY).name)
        assertEquals("Germany", place("UTC", Locale.Builder().setLanguage("en").setRegion("DE").build()).name)
    }

    /**
     * The network's country outranks the locale's, because it is where the phone *is* rather than
     * where it is configured to think it is — which is the whole difference while travelling.
     */
    @Test
    fun `the network's country beats the locale's`() {
        val p = Coarse.place(
            TimeZone.getTimeZone("UTC"),
            Locale.US,
            networkCountry = "fr",
        )
        assertEquals("France", p.name)
    }

    @Test
    fun `a zone that names a city outranks even the network`() {
        val p = Coarse.place(TimeZone.getTimeZone("Europe/Berlin"), Locale.US, networkCountry = "fr")
        assertEquals("Berlin", p.name)
    }

    @Test
    fun `an unassigned country code is not a place name`() {
        val p = Coarse.place(TimeZone.getTimeZone("UTC"), Locale.ROOT, networkCountry = "ZZ")
        assertFalse(p.known)
    }

    /**
     * The one way to get nothing, and the reason it is acceptable: a phone reporting UTC with no
     * SIM and a locale with no country has said nothing about where it is, and naming a place for
     * it would be a lie rather than a guess. This is documented rather than papered over — CI runs
     * in exactly that state, which is how it was found.
     */
    @Test
    fun `UTC with no SIM and no locale country is honestly nothing`() {
        assertFalse(Coarse.place(TimeZone.getTimeZone("UTC"), Locale.ROOT).known)
    }

    /** Any one of the three is enough, which is what a real phone always has. */
    @Test
    fun `any one of the three sources is enough`() {
        assertTrue(Coarse.place(TimeZone.getTimeZone("Europe/Paris"), Locale.ROOT).known)
        assertTrue(Coarse.place(TimeZone.getTimeZone("UTC"), Locale.ROOT, "FR").known)
        assertTrue(Coarse.place(TimeZone.getTimeZone("UTC"), Locale.FRANCE).known)
    }
}
