package com.gios.brightrecorder.tape

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filing a clip under its real place after the fact.
 *
 * The rename is what makes it acceptable to file a clip under a guess: a moment is four seconds
 * long and a location fix is not, so the name a clip gets when you press stop is very often the
 * region from the time zone. When the geocoder finally answers, this is what puts the truth on it.
 */
class LibraryRenameTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brtest-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun clipOn(place: String, at: Long): Clip {
        val name = Naming.fileName(place, at)
        File(dir, name).writeBytes(ByteArray(8))
        return Naming.parse(name)!!
    }

    @Test
    fun `a clip is refiled under the place that turned up late`() {
        val at = 1_770_000_000_000L
        val clip = clipOn("Paris", at)

        val renamed = Library.rename(dir, clip, "Rue de Lappe, Paris")

        assertEquals("Rue de Lappe, Paris", renamed?.place)
        assertTrue(File(dir, renamed!!.fileName).isFile)
        assertFalse(File(dir, clip.fileName).exists())
    }

    /** The tape must not reorder itself under the head, so the moment is the clip's, not the clock's. */
    @Test
    fun `renaming keeps the moment it was recorded`() {
        val at = 1_770_000_000_000L
        val clip = clipOn("Paris", at)

        val renamed = Library.rename(dir, clip, "Bastille, Paris")!!

        assertEquals(clip.startedAt, Naming.parse(renamed.fileName)!!.startedAt)
        assertEquals(clip.fileName.take(17), renamed.fileName.take(17))
    }

    @Test
    fun `renaming to the name it already has does nothing`() {
        val clip = clipOn("Paris", 1_770_000_000_000L)
        assertNull(Library.rename(dir, clip, "Paris"))
        assertTrue(File(dir, clip.fileName).isFile)
    }

    /** Two clips in the same second in the same place would collide; the first one keeps the name. */
    @Test
    fun `renaming onto an existing clip is refused`() {
        val at = 1_770_000_000_000L
        val clip = clipOn("Somewhere", at)
        clipOn("Bastille, Paris", at)

        assertNull(Library.rename(dir, clip, "Bastille, Paris"))
        assertTrue(File(dir, clip.fileName).isFile)
    }

    /** Deleted while the lookup was still running. */
    @Test
    fun `renaming a clip that is gone is refused`() {
        val clip = clipOn("Paris", 1_770_000_000_000L)
        File(dir, clip.fileName).delete()
        assertNull(Library.rename(dir, clip, "Bastille, Paris"))
    }

    @Test
    fun `a place with a slash in it still produces one file`() {
        val clip = clipOn("Somewhere", 1_770_000_000_000L)
        val renamed = Library.rename(dir, clip, "Fifth/Main, Springfield")!!
        assertTrue(File(dir, renamed.fileName).isFile)
        assertFalse(renamed.fileName.contains('/'))
    }
}
