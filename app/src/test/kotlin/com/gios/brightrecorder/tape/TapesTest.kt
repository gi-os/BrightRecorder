package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The shelf, and above all the migration onto it.
 *
 * Everything here is about not losing recordings. A tape is only a folder, so most of this is
 * cheap — but moving a pre-tapes store onto the shelf touches files that cannot be recorded
 * again, and a delete that took a folder with clips still in it would be the one unrecoverable
 * bug this app has.
 */
class TapesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun filesDir(): File = folder.newFolder("files")

    /** A real, readable clip — the migration is only interesting if the files are genuine. */
    private fun clip(dir: File, name: String, samples: Int = 100) {
        dir.mkdirs()
        RandomAccessFile(File(dir, name), "rw").use { raf ->
            raf.write(Wav.header(samples.toLong() * BYTES_PER_SAMPLE))
            raf.write(ByteArray(samples * BYTES_PER_SAMPLE))
        }
    }

    @Test
    fun `a new tape is a folder named the way clips are`() {
        val root = Tapes.root(filesDir())
        val t = Tapes.create(root, "Trip to Rome", 0L)
        assertNotNull(t)
        assertEquals("Trip to Rome", t!!.name)
        assertTrue(t.dirName, t.dirName.endsWith(" Trip to Rome"))
        assertTrue(Tapes.dirOf(root, t).isDirectory)
        assertTrue(t.isEmpty)
    }

    @Test
    fun `the shelf is in the order the tapes were started`() {
        val root = Tapes.root(filesDir())
        Tapes.create(root, "Third", 3_000_000L)
        Tapes.create(root, "First", 1_000_000L)
        Tapes.create(root, "Second", 2_000_000L)
        assertEquals(listOf("First", "Second", "Third"), Tapes.list(root).map { it.name })
    }

    @Test
    fun `a pattern survives being written and read back`() {
        val root = Tapes.root(filesDir())
        val t = Tapes.create(root, "Rain", 0L, Pattern.Waves)!!
        assertEquals(Pattern.Waves, Tapes.list(root).single().pattern)
        val changed = Tapes.setPattern(root, t, Pattern.Chevron)
        assertEquals(Pattern.Chevron, changed!!.pattern)
        assertEquals(Pattern.Chevron, Tapes.list(root).single().pattern)
    }

    @Test
    fun `a folder with no pattern file still gets a stable one`() {
        // A tape copied in from another phone, or made before patterns existed. It must look the
        // same on every launch, or the shelf rearranges itself visually for no reason.
        val root = Tapes.root(filesDir())
        val dir = File(root, Naming.folderName("Copied In", 0L)).apply { mkdirs() }
        val first = Tapes.read(dir)!!.pattern
        assertEquals(first, Tapes.read(dir)!!.pattern)
        assertEquals(Pattern.forName("Copied In"), first)
    }

    @Test
    fun `renaming keeps the creation stamp so the shelf does not reshuffle`() {
        val root = Tapes.root(filesDir())
        val t = Tapes.create(root, "Untitled", 1_500_000L)!!
        clip(Tapes.dirOf(root, t), Naming.fileName("Paris", 1_500_000L))

        val renamed = Tapes.rename(root, t, "Rome, at night")!!
        assertEquals("Rome, at night", renamed.name)
        assertEquals(t.createdAt, renamed.createdAt)
        // And the clips came with it, because the folder moved rather than its contents.
        assertEquals(1, renamed.clips)
        assertFalse(Tapes.dirOf(root, t).exists())
    }

    @Test
    fun `renaming onto an existing tape is refused`() {
        val root = Tapes.root(filesDir())
        val a = Tapes.create(root, "Alpha", 1_000_000L)!!
        Tapes.create(root, "Beta", 1_000_000L)
        assertNull(Tapes.rename(root, a, "Beta"))
        // And nothing was destroyed in finding that out.
        assertEquals(2, Tapes.list(root).size)
    }

    @Test
    fun `an empty tape can be taken off the shelf and a full one cannot`() {
        val root = Tapes.root(filesDir())
        val empty = Tapes.create(root, "Empty", 1L)!!
        val full = Tapes.create(root, "Full", 2L)!!
        clip(Tapes.dirOf(root, full), Naming.fileName("Somewhere", 2L))

        assertTrue(Tapes.delete(root, empty))
        // The one unrecoverable mistake: a recursive delete over recordings.
        assertFalse(Tapes.delete(root, Tapes.list(root).single { it.name == "Full" }))
        assertTrue(Tapes.dirOf(root, full).isDirectory)
        assertEquals(1, Tapes.list(root).size)
    }

    @Test
    fun `a pre-tapes store is moved onto the shelf with every clip intact`() {
        val files = filesDir()
        val legacy = File(files, "tape")
        clip(legacy, Naming.fileName("Bastille, Paris", 2_000_000L))
        clip(legacy, Naming.fileName("Kreuzberg, Berlin", 1_000_000L))
        clip(legacy, Naming.fileName("Trastevere, Rome", 3_000_000L))

        val tape = Tapes.migrateLegacy(files, now = 9_000_000L)
        assertNotNull(tape)
        assertEquals(3, tape!!.clips)
        assertEquals(Tapes.DEFAULT_NAME, tape.name)
        // Stamped with the earliest clip, so it sorts as the oldest tape rather than the newest.
        assertEquals(1_000_000L, tape.createdAt)
        assertFalse("the old folder should be gone once it is empty", legacy.exists())

        val moved = Library.scan(Tapes.dirOf(Tapes.root(files), tape))
        assertEquals(3, moved.size)
        assertEquals(
            listOf("Kreuzberg, Berlin", "Bastille, Paris", "Trastevere, Rome"),
            moved.map { it.place },
        )
    }

    @Test
    fun `migrating twice does nothing the second time`() {
        val files = filesDir()
        clip(File(files, "tape"), Naming.fileName("Somewhere", 1_000_000L))
        assertEquals(1, Tapes.migrateLegacy(files, 9L)!!.clips)
        assertNull(Tapes.migrateLegacy(files, 9L))
        assertEquals(1, Tapes.list(Tapes.root(files)).size)
    }

    @Test
    fun `an empty legacy folder is cleared away rather than becoming a tape`() {
        val files = filesDir()
        File(files, "tape").mkdirs()
        assertNull(Tapes.migrateLegacy(files, 9L))
        assertTrue(Tapes.list(Tapes.root(files)).isEmpty())
    }

    @Test
    fun `a stray folder on the shelf is ignored rather than read as a tape`() {
        val root = Tapes.root(filesDir())
        File(root, "not a tape").mkdirs()
        Tapes.create(root, "Real", 1L)
        assertEquals(listOf("Real"), Tapes.list(root).map { it.name })
    }

    @Test
    fun `length is the sum of the clips on it`() {
        val root = Tapes.root(filesDir())
        val t = Tapes.create(root, "Sums", 1L)!!
        clip(Tapes.dirOf(root, t), Naming.fileName("A", 10L), samples = 100)
        clip(Tapes.dirOf(root, t), Naming.fileName("B", 20L), samples = 250)
        assertEquals(350L, Tapes.read(Tapes.dirOf(root, t))!!.samples)
    }

    @Test
    fun `every pattern has a distinct label and cycles back round`() {
        val labels = Pattern.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        var p = Pattern.Plain
        repeat(Pattern.entries.size) { p = p.next() }
        assertEquals(Pattern.Plain, p)
    }
}
