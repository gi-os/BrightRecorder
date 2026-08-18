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

    /** The last resort of the last resort: nothing to go on at all. */
    @Test
    fun `no zone and no country is honestly nothing`() {
        val p = Coarse.place(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        assertFalse(p.known)
    }

    @Test
    fun `whatever the phone is actually set to produces something`() {
        assertTrue(Coarse.place().known)
    }
}
