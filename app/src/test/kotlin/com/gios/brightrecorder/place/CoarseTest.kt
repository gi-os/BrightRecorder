package com.gios.brightrecorder.place

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor under a clip's name.
 *
 * The time zone used to be the top tier here and it was the wrong answer in the case this app is
 * for: a phone set to `America/New_York` labelled every recording "New York" wherever in the world
 * it was, with a city's precision, which reads as a fact rather than a guess. What is left is the
 * country, and the network's first — that is where the phone is standing rather than where it thinks
 * it lives.
 */
class CoarseTest {

    @Test
    fun `the network's country is the answer when there is one`() {
        assertEquals("France", Coarse.place(Locale.US, networkCountry = "fr").name)
    }

    /** The whole point of the reordering: the network beats anything the phone is configured for. */
    @Test
    fun `the network's country beats the locale's`() {
        assertEquals("France", Coarse.place(Locale.US, networkCountry = "FR").name)
        assertEquals("Japan", Coarse.place(Locale.UK, networkCountry = "JP").name)
    }

    @Test
    fun `without a SIM the locale answers`() {
        assertEquals("France", Coarse.place(Locale.FRANCE).name)
    }

    @Test
    fun `a coarse place says it is coarse, so a real name can replace it`() {
        assertEquals(Fix.Coarse, Coarse.place(Locale.US, "FR").fix)
    }

    /**
     * The country is named in the phone's own language, which is the point of taking it from the
     * locale: a German phone files a clip under Deutschland, an English one under Germany.
     */
    @Test
    fun `the country is named in the phone's own language`() {
        assertEquals("Deutschland", Coarse.place(Locale.GERMANY, "DE").name)
        assertEquals(
            "Germany",
            Coarse.place(Locale.Builder().setLanguage("en").setRegion("US").build(), "DE").name,
        )
    }

    @Test
    fun `an unassigned country code is not a place name`() {
        assertFalse(Coarse.place(Locale.ROOT, networkCountry = "ZZ").known)
    }

    @Test
    fun `nonsense from the network is ignored rather than used`() {
        assertFalse(Coarse.place(Locale.ROOT, networkCountry = "").known)
        assertFalse(Coarse.place(Locale.ROOT, networkCountry = "12").known)
    }

    /** No SIM and a locale with no country is a phone that has said nothing about where it is. */
    @Test
    fun `no network and no locale country is honestly nothing`() {
        assertFalse(Coarse.place(Locale.ROOT).known)
    }

    @Test
    fun `either source on its own is enough`() {
        assertTrue(Coarse.place(Locale.ROOT, "FR").known)
        assertTrue(Coarse.place(Locale.FRANCE).known)
    }
}
