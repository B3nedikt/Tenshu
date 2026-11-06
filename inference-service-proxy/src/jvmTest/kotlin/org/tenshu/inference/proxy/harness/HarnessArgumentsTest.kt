package org.tenshu.inference.proxy.harness

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The face-recall harness's optional arguments (`FaceDetectionStrategyExperimentTest`).
 *
 * ⚠⚠ The harness runs on a phone and never in the gate, so these are the only check that a
 * **supplied** argument which yields nothing fails the run rather than reading as "omitted": an
 * empty orientation manifest would otherwise analyse every portrait preview sideways and still
 * write a complete, wrong report.
 */
class HarnessArgumentsTest {

    private val photos = listOf("a.jpg", "b.jpg", "c.jpg")
    private val known = listOf("single", "two-stage", "two-stage-grid2")
    private val root: File = createTempDirectory("in6-args").toFile()

    @AfterTest
    fun cleanUp() {
        root.walkTopDown().forEach { it.setReadable(true) }
        root.deleteRecursively()
    }

    private fun corpus(name: String, vararg files: String): File =
        File(root, name).apply { mkdirs(); files.forEach { File(this, it).writeText("x") } }

    // -e photos

    @Test
    fun omittedPhotosWithNoDefaultCorpusSkips() {
        assertEquals(emptyList(), HarnessArguments.photos(root, null))
    }

    @Test
    fun omittedPhotosReadsTheDefaultCorpus() {
        corpus(HarnessArguments.DEFAULT_PHOTOS, "b.jpg", "a.png", "notes.txt")
        assertEquals(listOf("a.png", "b.jpg"), HarnessArguments.photos(root, null).map { it.name })
    }

    @Test
    fun suppliedPhotosIsRead() {
        corpus("previews", "p.webp")
        assertEquals(listOf("p.webp"), HarnessArguments.photos(root, "previews").map { it.name })
    }

    @Test
    fun suppliedPhotosThatDoesNotExistFails() {
        corpus(HarnessArguments.DEFAULT_PHOTOS, "a.jpg")
        assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, "previws") }
    }

    @Test
    fun suppliedPhotosThatIsAFileFails() {
        File(root, "previews").writeText("x")
        assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, "previews") }
    }

    @Test
    fun suppliedPhotosThatIsUnreadableFails() {
        val dir = corpus("previews", "p.jpg")
        check(dir.setReadable(false)) { "cannot make the fixture unreadable" }
        assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, "previews") }
    }

    @Test
    fun suppliedPhotosWithNoPhotosFails() {
        corpus("previews", "notes.txt")
        assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, "previews") }
    }

    @Test
    fun duplicateBasenamesInTheCorpusFailNamingBothPaths() {
        // ⚠ The manifest and per-photo lookups key on the basename, so two `a.jpg`s in different
        // subdirectories would share one orientation — fail whether or not a manifest was supplied.
        corpus("previews/x", "a.jpg")
        corpus("previews/y", "a.jpg", "b.jpg")
        val supplied = assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, "previews") }
        assertTrue("x/a.jpg" in supplied.message!! && "y/a.jpg" in supplied.message!!, supplied.message)
        corpus("${HarnessArguments.DEFAULT_PHOTOS}/x", "a.jpg")
        corpus("${HarnessArguments.DEFAULT_PHOTOS}/y", "a.jpg")
        assertFailsWith<IllegalStateException> { HarnessArguments.photos(root, null) }
    }

    // -e orientations (file level)

    @Test
    fun omittedManifestFileMeansNoOrientation() {
        assertEquals(emptyMap(), HarnessArguments.orientationManifest(root, null, photos))
    }

    @Test
    fun suppliedManifestFileIsRead() {
        File(root, "o.txt").writeText("a.jpg 6\n")
        assertEquals(mapOf("a.jpg" to 6), HarnessArguments.orientationManifest(root, "o.txt", photos))
    }

    @Test
    fun suppliedManifestFileThatIsMissingOrUnreadableFails() {
        assertFailsWith<IllegalStateException> { HarnessArguments.orientationManifest(root, "o.txt", photos) }
        File(root, "dir.txt").mkdirs()
        assertFailsWith<IllegalStateException> { HarnessArguments.orientationManifest(root, "dir.txt", photos) }
        val unreadable = File(root, "u.txt").apply { writeText("a.jpg 6\n"); check(setReadable(false)) }
        assertFailsWith<IllegalStateException> { HarnessArguments.orientationManifest(root, unreadable.name, photos) }
    }

    @Test
    fun suppliedManifestFileThatIsEmptyFails() {
        File(root, "o.txt").writeText("")
        assertFailsWith<IllegalStateException> { HarnessArguments.orientationManifest(root, "o.txt", photos) }
    }

    // -e orientations (line level)

    @Test
    fun omittedManifestMeansNoOrientation() {
        assertEquals(emptyMap(), HarnessArguments.orientations(null, photos))
    }

    @Test
    fun suppliedManifestIsParsed() {
        assertEquals(mapOf("a.jpg" to 6, "b.jpg" to 1), HarnessArguments.orientations(listOf("a.jpg 6", "", "b.jpg 1"), photos))
    }

    @Test
    fun emptyManifestFails() {
        assertFailsWith<IllegalStateException> { HarnessArguments.orientations(emptyList(), photos) }
        assertFailsWith<IllegalStateException> { HarnessArguments.orientations(listOf("", "  "), photos) }
    }

    @Test
    fun anyMalformedLineFails() {
        for (bad in listOf("a.jpg", "a.jpg six", "a.jpg 6 extra", "a.jpg 0", "a.jpg 9")) {
            assertFailsWith<IllegalStateException>(bad) { HarnessArguments.orientations(listOf("b.jpg 1", bad), photos) }
        }
    }

    @Test
    fun contradictoryDuplicateFails() {
        assertFailsWith<IllegalStateException> { HarnessArguments.orientations(listOf("a.jpg 6", "a.jpg 8"), photos) }
    }

    @Test
    fun manifestCoveringNoPhotoFails() {
        assertFailsWith<IllegalStateException> { HarnessArguments.orientations(listOf("x.jpg 6"), photos) }
        assertFailsWith<IllegalStateException> { HarnessArguments.orientations(listOf("a.jpg 6"), emptyList()) }
    }

    @Test
    fun omittedStrategiesRunsAll() {
        assertEquals(known, HarnessArguments.strategies(null, known))
    }

    @Test
    fun requestedStrategiesKeepTheBaseline() {
        assertEquals(listOf("single", "two-stage-grid2"), HarnessArguments.strategies("two-stage-grid2", known))
    }

    @Test
    fun unknownOrEmptyStrategyFails() {
        assertFailsWith<IllegalStateException> { HarnessArguments.strategies("two-stage-grid3", known) }
        assertFailsWith<IllegalStateException> { HarnessArguments.strategies("", known) }
        assertTrue(HarnessArguments.strategies("single", known) == listOf("single"))
    }
}
