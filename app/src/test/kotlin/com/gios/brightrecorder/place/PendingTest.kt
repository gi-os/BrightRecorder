package com.gios.brightrecorder.place

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue of clips that know where they were but not what it is called.
 *
 * This is what turns "no signal when you recorded it" from permanent into temporary, so the thing
 * worth testing hardest is that a position is never lost while a lookup keeps failing.
 */
class PendingTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brpending-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a tape with nothing waiting has an empty queue and no file`() {
        assertTrue(Pending.list(dir).isEmpty())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a position survives being written and read back`() {
        Pending.add(dir, "clip.wav", 48.8570, 2.3700)
        val waiting = Pending.list(dir).single()
        assertEquals("clip.wav", waiting.fileName)
        assertEquals(48.8570, waiting.latitude, 1e-6)
        assertEquals(2.3700, waiting.longitude, 1e-6)
    }

    @Test
    fun `several clips can be waiting at once, in the order they were added`() {
        Pending.add(dir, "a.wav", 1.0, 2.0)
        Pending.add(dir, "b.wav", 3.0, 4.0)
        assertEquals(listOf("a.wav", "b.wav"), Pending.list(dir).map { it.fileName })
    }

    /** Otherwise a clip re-queued after a failed lookup would accumulate a line each time. */
    @Test
    fun `adding the same clip twice replaces its line rather than adding another`() {
        Pending.add(dir, "a.wav", 1.0, 2.0)
        Pending.add(dir, "a.wav", 9.0, 8.0)
        val waiting = Pending.list(dir).single()
        assertEquals(9.0, waiting.latitude, 1e-6)
    }

    @Test
    fun `removing the last one takes the file with it`() {
        Pending.add(dir, "a.wav", 1.0, 2.0)
        Pending.remove(dir, "a.wav")
        assertTrue(Pending.list(dir).isEmpty())
        assertEquals("the queue should leave nothing behind", 0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `pruning drops clips that are no longer on the tape`() {
        Pending.add(dir, "a.wav", 1.0, 2.0)
        Pending.add(dir, "gone.wav", 3.0, 4.0)
        Pending.prune(dir, setOf("a.wav"))
        assertEquals(listOf("a.wav"), Pending.list(dir).map { it.fileName })
    }

    @Test
    fun `pruning with everything present changes nothing`() {
        Pending.add(dir, "a.wav", 1.0, 2.0)
        assertFalse(Pending.prune(dir, setOf("a.wav")))
        assertEquals(1, Pending.list(dir).size)
    }

    /**
     * A place name can contain a comma, a full stop and an apostrophe, and a filename can contain
     * all three — which is why the file is tab-separated and why this is worth pinning.
     */
    @Test
    fun `a filename full of punctuation survives the round trip`() {
        val name = "2026-08-17 143205 Washington, D.C..wav"
        Pending.add(dir, name, 38.9, -77.0)
        assertEquals(name, Pending.list(dir).single().fileName)
    }

    @Test
    fun `a corrupt line is skipped rather than throwing`() {
        File(dir, "pending-places.tsv").writeText("rubbish\nb.wav\t1.0\t2.0\nalso\trubbish\there")
        assertEquals(listOf("b.wav"), Pending.list(dir).map { it.fileName })
    }

    @Test
    fun `removing something that was never queued is harmless`() {
        assertTrue(Pending.remove(dir, "nothing.wav"))
        assertTrue(Pending.list(dir).isEmpty())
    }
}
