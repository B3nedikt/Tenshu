package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.CompiledSpaceInsufficientException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.proxy.CompiledSweep
import org.tenshu.inference.proxy.CompiledTower
import org.tenshu.inference.proxy.catalog.TarFormatException
import org.tenshu.inference.proxy.catalog.TarTestEntry
import org.tenshu.inference.proxy.catalog.testTar
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prepare step's ordering and its marker (`ios-on-device-inference.md` §3.4).
 *
 * ⚠ Most cases use [RecordingCompiler], which stands in for Core ML and observes the cache from
 * inside each step: "the marker is written last" is a claim about what exists *while* the earlier
 * steps run, and only something called from inside them can see that.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreMlModelCacheTest {

    private val fixtures = CoreMlFixtures()
    private val vision: CoreMlTowerSpec = CoreMlTowerSpec.vision(VisionModel.PinnedSigLip)
    private val digest: String = "a".repeat(64)

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    private fun packageArchive(path: String = "archives/vision.tar"): String {
        fixtures.files.writeRawByteArray(
            path,
            testTar(
                TarTestEntry("vision_fp16.mlpackage", type = '5'),
                TarTestEntry("vision_fp16.mlpackage/Manifest.json", "{}".encodeToByteArray()),
                TarTestEntry("vision_fp16.mlpackage/Data/weights/weight.bin", ByteArray(2048) { 5 }),
            ),
        )
        return path
    }

    private fun markerExists(forDigest: String = digest): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/${forDigest.take(16)}/ready")

    /** Core ML's two calls, recorded, with the cache's own answer sampled inside each. */
    private inner class RecordingCompiler(
        private val failFirstOpen: Boolean = false,
        private val blockTheMove: Boolean = false,
    ) : CoreMlCompiler {
        var temporaryPath: String? = null
        lateinit var cache: CoreMlModelCache
        var compiles = 0
        var packageSeenAtCompile = false
        var readyDuringCompile: String? = null
        var readyDuringFirstOpen: String? = null
        var markerDuringFirstOpen = false
        var packageGoneAtFirstOpen = false

        override fun compile(packagePath: String): String {
            compiles++
            packageSeenAtCompile =
                NSFileManager.defaultManager.fileExistsAtPath("$packagePath/Data/weights/weight.bin")
            readyDuringCompile = cache.compiledPath(vision.role, digest)
            // `compileModel` leaves its result in a temporary place; the cache moves it.
            val temporary = "${NSTemporaryDirectory()}compiled-${Random.nextLong()}.mlmodelc"
            NSFileManager.defaultManager.createDirectoryAtPath(temporary, true, null, null)
            NSFileManager.defaultManager.createFileAtPath("$temporary/model.mil", null, null)
            temporaryPath = temporary
            // A plain file where the digest directory belongs: the move has nowhere to land.
            if (blockTheMove) {
                NSFileManager.defaultManager.createFileAtPath("${fixtures.root}/coreml/${digest.take(16)}", null, null)
            }
            return temporary
        }

        override fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec) {
            readyDuringFirstOpen = cache.compiledPath(vision.role, digest)
            markerDuringFirstOpen = markerExists()
            packageGoneAtFirstOpen =
                !NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/tmp/${spec.role}")
            if (failFirstOpen) throw CoreMlLoadException("the compiled model would not load")
        }
    }

    private fun cacheWith(compiler: RecordingCompiler, files: FileDatabase = fixtures.files): CoreMlModelCache =
        CoreMlModelCache(files, fixtures.root, compiler, osBuild = { "Version 26.6.1 (Build 23G1)" })
            .also { compiler.cache = it }

    @Test
    fun theMarkerIsWrittenAfterEveryOtherStep() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        val steps = ArrayList<CoreMlPrepareStep>()

        val path: String = runBlocking { cache.prepare(vision, packageArchive(), digest) { steps += it } }

        assertEquals(CoreMlPrepareStep.entries.toList(), steps)
        assertTrue(compiler.packageSeenAtCompile, "the unpacked package must exist when Core ML compiles it")
        assertNull(compiler.readyDuringCompile)
        // ⚠ The last step before the marker: the model is compiled, moved and about to be loaded,
        // and it must still read as not ready.
        assertNull(compiler.readyDuringFirstOpen)
        assertFalse(compiler.markerDuringFirstOpen, "the marker must not exist before the first open returns")
        assertTrue(compiler.packageGoneAtFirstOpen, "the unpacked package is deleted before the first open")

        assertEquals("${fixtures.root}/coreml/${digest.take(16)}/vision.mlmodelc", path)
        assertEquals(path, cache.compiledPath(vision.role, digest))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$path/model.mil"))
        assertEquals("Version 26.6.1 (Build 23G1)", cache.preparedOnOsBuild(digest))
    }

    @Test
    fun aMissingMarkerReadsAsNotReadyAndThePrepareIsRedone() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        val archive: String = packageArchive()
        val path: String = runBlocking { cache.prepare(vision, archive, digest) }

        // Everything but the marker: what an interruption between the last two steps leaves.
        fixtures.files.deleteFile("coreml/${digest.take(16)}/ready")

        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "the compiled model is still on disk")
        assertNull(cache.compiledPath(vision.role, digest))
        runBlocking { cache.prepare(vision, archive, digest) }
        assertEquals(2, compiler.compiles)
        assertNotNull(cache.compiledPath(vision.role, digest))
    }

    @Test
    fun aFailedFirstOpenLeavesNothingThatReadsAsReady() {
        val compiler = RecordingCompiler(failFirstOpen = true)
        val cache: CoreMlModelCache = cacheWith(compiler)

        assertFailsWith<CoreMlLoadException> { runBlocking { cache.prepare(vision, packageArchive(), digest) } }

        assertFalse(markerExists())
        assertNull(cache.compiledPath(vision.role, digest))
    }

    @Test
    fun aFailedMoveDeletesTheCompiledModelCoreMlLeftInItsTemporaryPlace() {
        val compiler = RecordingCompiler(blockTheMove = true)
        val cache: CoreMlModelCache = cacheWith(compiler)
        val steps = ArrayList<CoreMlPrepareStep>()

        assertFailsWith<CoreMlPrepareException> {
            runBlocking { cache.prepare(vision, packageArchive(), digest) { steps += it } }
        }

        assertEquals(CoreMlPrepareStep.MOVE, steps.last())
        val temporary: String = assertNotNull(compiler.temporaryPath)
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(temporary),
            "the compiled model is as large as the tower; a failed move must not leave it in the temporary directory",
        )
        assertFalse(markerExists())
        assertNull(cache.compiledPath(vision.role, digest))
    }

    @Test
    fun aMarkerNamingAnotherDigestReadsAsNotReady() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        runBlocking { cache.prepare(vision, packageArchive(), digest) }

        // The same directory tag, a different archive.
        val sibling: String = "a".repeat(16) + "b".repeat(48)

        assertNotNull(cache.compiledPath(vision.role, digest))
        assertNull(cache.compiledPath(vision.role, sibling))
    }

    @Test
    fun aReadyTowerIsNotPreparedTwice() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        val archive: String = packageArchive()

        val first: String = runBlocking { cache.prepare(vision, archive, digest) }
        val second: String = runBlocking { cache.prepare(vision, archive, digest) }

        assertEquals(first, second)
        assertEquals(1, compiler.compiles)
    }

    @Test
    fun anArchiveThatIsNotOnePackageIsRefusedBeforeItIsCompiled() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        fixtures.files.writeRawByteArray(
            "archives/two.tar",
            testTar(
                TarTestEntry("a.mlpackage/Manifest.json", "{}".encodeToByteArray()),
                TarTestEntry("b.mlpackage/Manifest.json", "{}".encodeToByteArray()),
            ),
        )

        assertFailsWith<TarFormatException> { runBlocking { cache.prepare(vision, "archives/two.tar", digest) } }

        assertEquals(0, compiler.compiles)
        assertFalse(markerExists())
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/tmp/vision"))
    }

    @Test
    fun tooLittleFreeSpaceIsRefusedBeforeAnythingIsUnpacked() {
        val compiler = RecordingCompiler()
        val cramped = object : FileDatabase by fixtures.files {
            override fun availableSpaceBytes(): Long = 1024
        }
        val cache: CoreMlModelCache = cacheWith(compiler, cramped)
        val steps = ArrayList<CoreMlPrepareStep>()

        // ⚠ With the two figures as values (§14.18, M8): what a screen words, in its own language.
        val refused = assertFailsWith<CompiledSpaceInsufficientException> {
            runBlocking { cache.prepare(vision, packageArchive(), digest) { steps += it } }
        }
        assertEquals(1024L, refused.freeBytes)
        assertTrue(refused.neededBytes > refused.freeBytes)

        assertEquals(listOf(CoreMlPrepareStep.CHECK_SPACE), steps)
        assertEquals(0, compiler.compiles)
    }

    @Test
    fun aSweepKeepsTheLiveDigestsAndDropsTheRest() {
        val compiler = RecordingCompiler()
        val cache: CoreMlModelCache = cacheWith(compiler)
        val old: String = "c".repeat(64)
        runBlocking {
            cache.prepare(vision, packageArchive(), old)
            cache.prepare(vision, packageArchive(), digest)
        }
        assertTrue(cache.sizeOnDisk() > 0)

        cache.sweep(setOf(digest))

        assertNull(cache.compiledPath(vision.role, old))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/${old.take(16)}"))
        assertNotNull(cache.compiledPath(vision.role, digest))
    }

    @Test
    fun theFixtureTowerIsCompiledByCoreMlAndThenLoads() {
        val archive: String = fixtures.archive("fixture_vision") ?: return
        val cache: CoreMlModelCache = fixtures.cache()
        val steps = ArrayList<CoreMlPrepareStep>()

        val path: String = runBlocking { cache.prepare(vision, archive, digest) { steps += it } }

        assertEquals(CoreMlPrepareStep.entries.toList(), steps)
        assertEquals(path, cache.compiledPath(vision.role, digest))
        assertEquals(768, CoreMlSession.open(path, vision).predict(FloatArray(vision.inputCount) { 0.5f }).size)
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/tmp/vision"),
            "the unpacked package is deleted once it is compiled",
        )
    }

    @Test
    fun aFixtureThatIsAnotherModelIsNeverMarkedReady() {
        val archive: String = fixtures.archive("fixture_vision_224") ?: return
        val cache: CoreMlModelCache = fixtures.cache()

        assertFailsWith<CoreMlRejectedModelException> { runBlocking { cache.prepare(vision, archive, digest) } }

        assertFalse(markerExists())
        assertNull(cache.compiledPath(vision.role, digest))
    }

    // ─── What the prepare step asks of the cache (§14.17).

    @Test
    fun aMarkerThatIsThereAndGivesNoBytesIsUnreadableNotAbsentAndIsNotCompiledOver(): Unit = runBlocking {
        val compiler = RecordingCompiler()
        val archive = packageArchive()
        cacheWith(compiler).prepare(vision, archive, digest)
        val marker = "coreml/${digest.take(16)}/ready"
        // What a phone answers before its first unlock: the file is there and gives nothing.
        val locked = object : FileDatabase by fixtures.files {
            override fun readBytes(filePath: String): TByteArray? = if (filePath == marker) null else fixtures.files.readBytes(filePath)
        }
        val cache = cacheWith(compiler, locked)

        assertIs<CompiledTower.Unreadable>(cache.tower(vision.role, digest))
        assertFailsWith<CoreMlPrepareException> { cache.compiledPath(vision.role, digest) }
        // ⚠⚠ Read as absent, the tower beside it was deleted and compiled again.
        assertFailsWith<CoreMlPrepareException> { cache.prepare(vision, archive, digest) }
        assertEquals(1, compiler.compiles)
        assertTrue(markerExists())
        assertIs<CompiledTower.Ready>(cacheWith(compiler).tower(vision.role, digest))
    }

    @Test
    fun aStepThatIsRefusedLeavesNoMarkerAndNoDirectory(): Unit = runBlocking {
        val compiler = RecordingCompiler()
        val cache = cacheWith(compiler)
        class Stop : Exception()

        // The question the prepare step asks before each step answers "no" at the last one.
        assertFailsWith<Stop> {
            cache.prepare(vision, packageArchive(), digest) { step -> if (step == CoreMlPrepareStep.MARK_READY) throw Stop() }
        }

        assertFalse(markerExists())
        assertEquals(CompiledTower.Absent, cache.tower(vision.role, digest))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/${digest.take(16)}"))
    }

    @Test
    fun aDiscardRemovesTheTowerAndASweepSaysWhatItRemoved(): Unit = runBlocking {
        val cache = cacheWith(RecordingCompiler())
        assertEquals(CompiledSweep.Swept(emptyList()), cache.sweep(emptySet()), "no directory is nothing to sweep")
        cache.prepare(vision, packageArchive(), digest)

        // The live tower stays; the emptied staging area is what goes.
        assertEquals(CompiledSweep.Swept(listOf("tmp")), cache.sweep(setOf(digest)))
        assertIs<CompiledTower.Ready>(cache.tower(vision.role, digest))
        cache.discard(digest)
        assertEquals(CompiledTower.Absent, cache.tower(vision.role, digest))

        cache.prepare(vision, packageArchive(), digest)
        val swept = assertIs<CompiledSweep.Swept>(cache.sweep(emptySet()))
        assertTrue(digest.take(16) in swept.removed, "the tower no record names was not swept: ${swept.removed}")
        assertEquals(CompiledTower.Absent, cache.tower(vision.role, digest))
    }

    @Test
    fun aStopAtAnyOfTheSevenStepsLeavesNothingNotEvenCoreMlsOwnOutput(): Unit = runBlocking {
        class Stop : Exception()
        CoreMlPrepareStep.entries.forEach { stopAt ->
            val compiler = RecordingCompiler()
            val cache = cacheWith(compiler)

            assertFailsWith<Stop>("stopped at $stopAt") {
                cache.prepare(vision, packageArchive(), digest) { step -> if (step == stopAt) throw Stop() }
            }

            assertFalse(markerExists(), "a marker after a stop at $stopAt")
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/${digest.take(16)}"), "a directory after a stop at $stopAt")
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${fixtures.root}/coreml/tmp/vision"), "the package after a stop at $stopAt")
            // ⚠⚠ Core ML's output is outside the cache: a stop between the compile and the move
            // left it there, as large as the model, at every background-during-compile.
            compiler.temporaryPath?.let { temporary ->
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath(temporary), "Core ML's output was left after a stop at $stopAt")
            }
        }
    }

    @Test
    fun aDiscardTakesTheMarkerFirst(): Unit = runBlocking {
        val cache = cacheWith(RecordingCompiler())
        cache.prepare(vision, packageArchive(), digest)

        cache.discard(digest)

        assertFalse(markerExists())
        assertEquals(CompiledTower.Absent, cache.tower(vision.role, digest))
        // And a directory left with no marker (a delete that died half-way) is not ready.
        cache.prepare(vision, packageArchive(), digest)
        NSFileManager.defaultManager.removeItemAtPath("${fixtures.root}/coreml/${digest.take(16)}/ready", error = null)
        assertEquals(CompiledTower.Absent, cache.tower(vision.role, digest))
    }
}
