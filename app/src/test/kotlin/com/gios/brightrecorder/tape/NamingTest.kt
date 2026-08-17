package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Names, round-tripped.
 *
 * The filename is the only record of where and when a clip was made — there is no database — so a
 * name that cannot be parsed back is a recording that has lost half of what it was for. These are
 * mostly tests about place names, because those come from a reverse geocoder and arrive containing
 * every character a filesystem objects to.
 */
class NamingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun `a filename leads with a sortable timestamp`() {
        val name = Naming.fileName("Bastille, Paris", at(2026, 8, 17, 14, 32, 5), utc)
        assertEquals("2026-08-17 143205 Bastille, Paris.wav", name)
    }

    @Test
    fun `the display title leads with the place`() {
        val title = Naming.title("Bastille, Paris", at(2026, 8, 17, 14, 32), utc)
        assertEquals("Bastille, Paris at 17 Aug 2026, 14:32", title)
    }

    @Test
    fun `filenames sort chronologically as plain strings`() {
        // This is what makes the directory a tape. If it ever stops holding, clips play out of
        // order and the continuous-tape idea quietly breaks.
        val morning = Naming.fileName("Zanzibar", at(2026, 8, 17, 9, 0), utc)
        val evening = Naming.fileName("Aachen", at(2026, 8, 17, 21, 0), utc)
        val nextYear = Naming.fileName("Aachen", at(2027, 1, 1, 0, 0), utc)
        val sorted = listOf(nextYear, evening, morning).sorted()
        assertEquals(listOf(morning, evening, nextYear), sorted)
    }

    @Test
    fun `a name round trips through parse`() {
        val stamp = at(2026, 8, 17, 14, 32, 5)
        val name = Naming.fileName("Kreuzberg, Berlin", stamp, utc)
        val parsed = Naming.parse(name)
        assertNotNull(parsed)
        assertEquals("Kreuzberg, Berlin", parsed!!.place)
    }

    @Test
    fun `a place with punctuation survives`() {
        // Real answers from real geocoders: hyphens, full stops, commas, apostrophes.
        for (place in listOf(
            "Saint-Germain-des-Prés",
            "Washington, D.C.",
            "L'Aquila",
            "Stoke-on-Trent",
        )) {
            val name = Naming.fileName(place, at(2026, 3, 4, 5, 6, 7), utc)
            assertEquals(place, Naming.parse(name)?.place)
        }
    }

    @Test
    fun `a slash in a place name cannot escape the directory`() {
        // Unstripped, this writes the clip into a directory that does not exist and the recording
        // is lost at the moment it is filed.
        val cleaned = Naming.clean("Elsass/Alsace")
        assertTrue(cleaned, !cleaned.contains('/'))
        val name = Naming.fileName("../../etc/passwd", at(2026, 1, 1, 0, 0), utc)
        assertTrue(name, !name.contains('/'))
    }

    @Test
    fun `an empty or blank place becomes somewhere`() {
        assertEquals(Naming.NOWHERE, Naming.clean(""))
        assertEquals(Naming.NOWHERE, Naming.clean("   "))
        assertEquals(Naming.NOWHERE, Naming.clean("///"))
    }

    @Test
    fun `runs of whitespace collapse`() {
        assertEquals("Porta Nuova, Milan", Naming.clean("Porta   Nuova,\tMilan "))
    }

    @Test
    fun `a trailing dot is kept`() {
        // It reads like the Windows "no trailing period" trap, but the place is always followed by
        // the extension, so nothing here ever ends in a dot. Trimming it filed every clip from
        // Washington under "Washington, D.C" instead.
        assertEquals("St Albans.", Naming.clean("St Albans."))
        assertTrue(Naming.fileName("Washington, D.C.", 0L, utc).endsWith("D.C..wav"))
    }

    @Test
    fun `a very long place name is truncated rather than rejected`() {
        val long = "A".repeat(300)
        assertTrue(Naming.clean(long).length <= 60)
    }

    @Test
    fun `a file that is not ours does not parse`() {
        assertNull(Naming.parse("notes.txt"))
        assertNull(Naming.parse("recording.wav"))
        assertNull(Naming.parse("2026-13-45 999999 Nowhere.wav"))
        assertNull(Naming.parse(".recording-1755440000000.wav"))
    }

    @Test
    fun `a clip with no place still parses`() {
        val name = Naming.fileName(Naming.NOWHERE, at(2026, 8, 17, 2, 14), utc)
        assertEquals(Naming.NOWHERE, Naming.parse(name)?.place)
    }
}
