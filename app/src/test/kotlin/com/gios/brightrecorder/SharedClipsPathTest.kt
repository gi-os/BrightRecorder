package com.gios.brightrecorder

import com.gios.brightrecorder.tape.Tapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The FileProvider's declared path against the directory the app actually uses.
 *
 * These drifted once and nothing caught it. The shelf moved from `files/tape/` to `files/tapes/`
 * and `shared_clips.xml` kept the old spelling, which meant every share handed BrightChat a URI
 * that could not be opened — while reporting success, because `FileProvider.getUriForFile`
 * matches its roots with a bare `startsWith` and no separator boundary. `files/tapes/x.wav`
 * matched the root `files/tape`, lost one character too many off the front, and came back as a
 * perfectly well-formed URI pointing at nothing.
 *
 * A resource file and a Kotlin constant have no way to disagree loudly, so this is the thing that
 * makes them disagree loudly. The XML is read as text rather than through the Android resource
 * system on purpose: this has to run on the JVM, and the point is the literal string in the file.
 */
class SharedClipsPathTest {

    private val xml: String by lazy {
        val f = File("src/main/res/xml/shared_clips.xml")
        assertTrue("shared_clips.xml not found at ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * The `path` attribute of the one `<files-path>` element, without its trailing slash.
     *
     * Comments are stripped first, and that is not hypothetical tidiness: the comment in
     * `shared_clips.xml` explains why the path is named by quoting the alternative it rejects —
     * `<files-path path="." />` — and this read that quotation instead of the real element on its
     * first run, which made the test fail against a file that was already correct.
     */
    private val declaredPath: String by lazy {
        val body = xml.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val match = Regex("""<files-path[^>]*\bpath\s*=\s*"([^"]*)"""").find(body)
        requireNotNull(match) { "no <files-path path=...> in shared_clips.xml" }
            .groupValues[1]
            .trim()
            .trimEnd('/')
    }

    @Test
    fun `the provider serves the directory the shelf actually lives in`() {
        val shelf = Tapes.root(File("build/tmp/sharedClipsPathTest")).name
        assertEquals(
            "shared_clips.xml serves '$declaredPath' but Tapes.root is '$shelf' — " +
                "every share would hand out a URI that resolves to nothing",
            shelf,
            declaredPath,
        )
    }

    /**
     * The old value, specifically. `tape` is not merely wrong — it is a prefix of the right
     * answer, which is exactly why the bug was silent, so it is worth naming.
     */
    @Test
    fun `the legacy folder is not what is served`() {
        assertTrue(
            "shared_clips.xml is back to the pre-shelf 'tape/' folder, which migrateLegacy deletes",
            declaredPath != "tape",
        )
    }

    /**
     * A bare `.` would serve the whole of `filesDir`, which also holds the crash log and the
     * report token. Any app that could ask for a URI could then ask for those.
     */
    @Test
    fun `the provider is not pointed at the whole of files`() {
        assertTrue("shared_clips.xml serves all of filesDir", declaredPath.isNotBlank())
        assertTrue("shared_clips.xml serves all of filesDir", declaredPath != ".")
    }
}
