package org.tenshu.inference.harness.ios

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.IosFileDatabase
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the harness may and may not say (`ios-on-device-inference.md` §4.1, §4.3).
 *
 * ⚠ These run on a simulator, so none of them reads a timing. They pin the refusal, the skip paths
 * that must still write the report, and that the whole pipeline — unpack, compile, open, decode,
 * predict, the vector file — holds together against a model Core ML really compiled.
 */
@OptIn(ExperimentalForeignApi::class)
class In3mHarnessTest {

    private val documents: String = "${NSTemporaryDirectory()}in3m-test-${Random.nextLong()}"
    private val files = IosFileDatabase(root = documents)
    private val logged = ArrayList<String>()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(documents, error = null)
    }

    private object FakeProbes : DeviceProbes {
        override fun footprint() = Footprint(current = 20_000_000, ledgerPeak = 25_000_000)
        override fun thermalState() = 0
        override fun lowPowerMode() = false
        override fun batteryLevel(): Double? = 1.0
        override fun batteryState() = "full"
        override fun freeBytes(path: String): Long? = 64_000_000_000
        override fun osBuild() = "Version 26.6.1 (Build 23G1)"
        override fun machine() = "iPhone18,5"
        override fun physicalMemory() = 8_000_000_000L
    }

    /** The harness with the simulator refusal forced off: §15.11.1's method for testing the rest. */
    private fun forcedOntoADevice() = In3mHarness(documents, { logged += it }, isSimulator = false, probes = FakeProbes)

    private fun report(): String? = files.readBytes(In3mHarness.REPORT)?.get()?.decodeToString()

    @Test
    fun aSimulatorBuildRefusesAndWritesNothing() {
        // Everything a run needs is present, so only the refusal can stop it.
        files.writeRawByteArray("vision_fp16.mlpackage.tar", ByteArray(1024))
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))
        writeCorpusManifest(files, "a.gif")

        val outcome: In3mOutcome = In3mHarness(documents) { logged += it }.run("coreml-ane", "vision", recompile = false)

        assertEquals(In3mOutcome.REFUSED_SIMULATOR, outcome)
        assertEquals(listOf("simulator — timings meaningless, ignoring"), logged)
        assertNull(report(), "a refused run must leave no report that could be read as a measurement")
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$documents/coreml"))
    }

    @Test
    fun forcedOffTheRefusalItProceedsToNoModelAndWritesTheReport() {
        val outcome: In3mOutcome = forcedOntoADevice().run("coreml-ane", "vision", recompile = false)

        assertEquals(In3mOutcome.NO_MODEL, outcome)
        val text: String = assertNotNull(report())
        assertTrue("SKIPPED: no model — expected Documents/vision_fp16.mlpackage.tar" in text, text)
        assertTrue("outcome: NO_MODEL" in text, text)
        // D12 ①'s gate reads this; the report is the only place the phone's figure is recorded (§14.2).
        assertTrue("physicalMemory: 8000000000 bytes (7.45 GiB)" in text, text)
    }

    @Test
    fun photosWithNoCorpusManifestAreRefusedNotMeasured() {
        files.writeRawByteArray("vision_fp16.mlpackage.tar", ByteArray(1024))
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))

        val outcome: In3mOutcome = forcedOntoADevice().run("coreml-ane", "vision", recompile = false)

        assertEquals(In3mOutcome.CORPUS_REFUSED, outcome)
        val text: String = assertNotNull(report())
        assertTrue("REFUSED: no corpus manifest — expected Documents/in3m-corpus.txt" in text, text)
        assertTrue("outcome: CORPUS_REFUSED" in text, text)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$documents/coreml"), "nothing is prepared for a refused corpus")
    }

    @Test
    fun aCorpusWithANamedFileMissingIsRefused() {
        files.writeRawByteArray("vision_fp16.mlpackage.tar", ByteArray(1024))
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))
        files.writeRawByteArray("photos/b.gif", onePixelGif(0x00, 0xFF, 0x00))
        writeCorpusManifest(files, "a.gif", "b.gif")
        files.deleteFile("photos/b.gif")

        assertEquals(In3mOutcome.CORPUS_REFUSED, forcedOntoADevice().run("coreml-ane", "vision", recompile = false))
        assertTrue("REFUSED: the corpus names b.gif, which is not in Documents/photos/" in assertNotNull(report()))
    }

    @Test
    fun aCorpusWithANamedFileOfOtherBytesIsRefused() {
        files.writeRawByteArray("vision_fp16.mlpackage.tar", ByteArray(1024))
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))
        writeCorpusManifest(files, "a.gif")
        // The same name and the same size, pushed from another corpus.
        files.writeRawByteArray("photos/a.gif", onePixelGif(0x00, 0x00, 0xFF))

        assertEquals(In3mOutcome.CORPUS_REFUSED, forcedOntoADevice().run("coreml-ane", "vision", recompile = false))
        assertTrue("REFUSED: a.gif has sha256 " in assertNotNull(report()))
    }

    @Test
    fun anUnknownRowIsReportedNotGuessed() {
        val outcome: In3mOutcome = forcedOntoADevice().run("coreml-gpu", "vision", recompile = false)

        assertEquals(In3mOutcome.UNKNOWN_ROW, outcome)
        assertTrue("unknown row 'coreml-gpu'" in assertNotNull(report()))
    }

    @Test
    fun anArchiveThatIsNotATowerIsAFailedRunWithAReport() {
        files.writeRawByteArray("vision_fp16.mlpackage.tar", ByteArray(4096) { 3 })
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))
        writeCorpusManifest(files, "a.gif")

        val outcome: In3mOutcome = forcedOntoADevice().run("coreml-ane", "vision", recompile = false)

        assertEquals(In3mOutcome.FAILED, outcome)
        assertTrue("FAILED: TarFormatException" in assertNotNull(report()))
    }

    @Test
    fun theWholePipelineRunsAgainstTheFixtureTower() {
        val fixtures: String? = NSProcessInfo.processInfo.environment["TENSHU_COREML_FIXTURES"] as? String
        if (fixtures == null) {
            println("SKIPPED: TENSHU_COREML_FIXTURES is not set, so the fixture tower cannot be read by path")
            return
        }
        NSFileManager.defaultManager.createDirectoryAtPath(documents, true, null, null)
        NSFileManager.defaultManager.copyItemAtPath(
            "$fixtures/fixture_vision.mlpackage.tar",
            "$documents/vision_fp16.mlpackage.tar",
            null,
        )
        files.writeRawByteArray("photos/a.gif", onePixelGif(0xFF, 0x00, 0x00))
        files.writeRawByteArray("photos/b.gif", onePixelGif(0x00, 0xFF, 0x00))
        files.writeRawByteArray("photos/c.gif", onePixelGif(0x00, 0x00, 0xFF))
        files.writeRawByteArray("photos/notes.txt", "not a photograph".encodeToByteArray())
        // Left by an earlier push of another corpus: on the phone, in the directory, not named.
        files.writeRawByteArray("photos/stand-in-1.gif", onePixelGif(0x10, 0x20, 0x30))
        files.writeRawByteArray("photos/stand-in-2.gif", onePixelGif(0x30, 0x20, 0x10))
        val corpusId: String = writeCorpusManifest(files, "a.gif", "b.gif", "c.gif", "notes.txt")

        assertEquals(In3mOutcome.MEASURED, forcedOntoADevice().run("coreml-ane", "vision", recompile = false))
        assertEquals(In3mOutcome.MEASURED, forcedOntoADevice().run("coreml-cpu", "vision", recompile = false))

        val text: String = assertNotNull(report())
        // ⚠ Six files in the directory, four in the corpus: the two extra are listed, never embedded.
        assertTrue("corpus: $corpusId  4 photos, each checked by size and sha256" in text, text)
        assertTrue("photos: 4 named by corpus ${corpusId.take(12)}  embedded 3  unreadable 1  failed 0" in text, text)
        assertTrue(
            "not in the corpus manifest, ignored: 2 file(s) in Documents/photos — stand-in-1.gif, stand-in-2.gif" in text,
            text,
        )
        assertTrue("fewer than 200 embedded: NOT an IN-3m figure" in text, text)
        // Every step of the prepare, timed, on the first launch; the marker short-circuits the second.
        assertTrue("COMPILE" in text && "MARK_READY" in text && "prepare total:" in text, text)
        assertTrue("already compiled (marker present)" in text, text)
        assertTrue("open (CPU_ONLY)" in text && "open (CPU_AND_NEURAL_ENGINE)" in text, text)
        // The second row is compared with the vectors the first one kept.
        assertTrue(
            Regex("""cosine vs in3m-vectors-coreml-ane-Version26\.6\.1Build23G1-[0-9a-f]{12}-p1-c[0-9a-f]{8}\.txt \(coreml-ane, .*\): shared 3""")
                .containsMatchIn(text),
            text,
        )
        val kept: String = filesIn(documents).single { it.startsWith("in3m-vectors-coreml-cpu-") }
        val stored: VectorFile.Stored = assertNotNull(VectorFile.read(files, kept))
        assertEquals(setOf("a.gif", "b.gif", "c.gif"), stored.vectors.keys)
        assertEquals(corpusId, stored.fingerprint.corpusId)
        // The fingerprint is the archive the run really used, as the report states it.
        assertTrue("sha256 ${stored.fingerprint.archiveSha256}" in text, text)
        assertEquals("vision", stored.fingerprint.tower)
    }

    /** A 1 × 1 GIF of one colour: the smallest file ImageIO decodes as an image. */
    private fun onePixelGif(red: Int, green: Int, blue: Int): ByteArray = intArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
        red, green, blue, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    ).map { it.toByte() }.toByteArray()
}
