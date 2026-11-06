package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.path
import org.tenshu.file.impl.BackfillThumbnailClassesImplementation
import org.tenshu.file.impl.ThumbnailClassMinter
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.core.number.TInteger
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.system.BackfillThumbnailClasses
import org.tenshu.core.toTByteArray
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thumbnail-class backfill end to end (`drive-synced-thumbnails.md` §15.3 D-6) through the real op, the real store
 * enumeration and the real write + emit — with `GetPreview` and the clip encoder faked, because on
 * the JVM neither can render anything a test could assert on.
 *
 * What this pins: the pass finds `File` rows through the store (not the folder tree), reads the
 * realm's wanted set from other devices' rows, mints on files with local bytes only, stores each
 * class at the upload's own blob path, emits it under the upload's own property id, latches a
 * marker keyed on the wanted set — and never declares anything itself.
 */
class BackfillThumbnailClassesOpTest {

    private val clock = org.tenshu.core.common.FakeClock(initial = 30L * 24 * 60 * 60 * 1000)

    private val fakeStill = ByteArray(2_048) { (it * 3 % 251).toByte() }
    private val fakeClip = ByteArray(1_000) { (it % 13).toByte() }
    private val fileBytes = ByteArray(4_096) { (it * 7 % 251).toByte() }

    @Test
    fun mintsTheWantedStillForAPhotoHeldLocally_andLatches() = runTest {
        val h = harness(renderer = null)
        h.file("/a.jpg", contentId = "content-a", local = true)
        h.file("/evicted.jpg", contentId = "content-evicted", local = false)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        val path = FileBytesPropertyWriter.thumbnailPath("content-a", GetStoredThumbnail.CLASS_XLARGE)
        assertContentEquals(fakeStill, h.proxy.fileDatabase.readBytes(path)?.get(), "the hero still lands at the upload's own blob path")
        val emit = h.emitter.calls.single()
        assertEquals("/a.jpg", emit.entityId)
        assertEquals(File.ThumbnailXLarge.id, emit.propertyId, "emitted under the upload's own property id")
        assertEquals(path, emit.filePath)
        assertNull(
            h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-evicted", GetStoredThumbnail.CLASS_XLARGE)),
            "a file whose bytes are not here has nothing to mint from",
        )
        assertEquals(
            ThumbnailClassBackfill.markerFor(setOf(GetStoredThumbnail.CLASS_XLARGE)),
            h.proxy.executionContext.readThumbnailBackfillMarker("device-1"),
        )
        assertTrue(
            h.proxy.executionContext.readWantedThumbnailClasses("device-1").isEmpty(),
            "the pass reads presence by stat, never through the pull path, so it declares nothing itself",
        )

        h.run()
        assertEquals(1, h.emitter.calls.size, "the second walk finds the class present and emits nothing")
        assertEquals(1, h.previewCalls, "and renders nothing: present is a stat, not a render")
    }

    /**
     * A file `GetPreview` declines is recorded beside its blob path and never rendered
     * again — until the mint format moves, when the recorded "no" is worth asking again.
     */
    @Test
    fun aDeclinedFileIsRecordedOnceAndRetriedOnlyWhenTheFormatMoves() = runTest {
        val h = harness(renderer = null, declines = setOf("/undecodable.jpg"))
        h.file("/undecodable.jpg", contentId = "content-bad", local = true)
        h.file("/a.jpg", contentId = "content-a", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()
        val sentinel = BackfillThumbnailClassesImplementation.thumbnailDeclinePath("content-bad", GetStoredThumbnail.CLASS_XLARGE)
        assertEquals(
            ThumbnailClassBackfill.MINT_FORMAT,
            h.proxy.fileDatabase.readBytes(sentinel)?.get()?.decodeToString(),
            "the decline is recorded with the format it was recorded under",
        )
        assertNull(h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-bad", GetStoredThumbnail.CLASS_XLARGE)))
        assertEquals(listOf(File.ThumbnailXLarge.id), h.emitter.calls.map { it.propertyId }, "only the good file emits")
        assertEquals(2, h.previewCalls)

        h.run()
        assertEquals(2, h.previewCalls, "the recorded decline is not rendered again")

        // A stale sentinel — recorded under an older mint format — is a "no" worth re-asking.
        h.proxy.fileDatabase.writeRawByteArray(sentinel, "v0".encodeToByteArray())
        h.run()
        assertEquals(3, h.previewCalls, "a stale-format decline is retried once")
        assertEquals(ThumbnailClassBackfill.MINT_FORMAT, h.proxy.fileDatabase.readBytes(sentinel)?.get()?.decodeToString(), "and re-recorded under the current format")
    }

    /**
     * §17: a detailed frame — a screenshot, a page of text — is ~500 KB at 1024 px q80, over a
     * 256 KB cap. ⚠ 400 KB at the first step pins both halves: the render steps down rather than declining, and the cap admits what the step produced.
     */
    @Test
    fun anOverCapStillStepsItsQualityDownBeforeItIsDeclined() = runTest {
        val atDefault = ByteArray(600 * 1024) { 1 }
        val atStep = ByteArray(400 * 1024) { 2 }
        val h = harness(renderer = null, still = { quality -> if (quality == null) atDefault else atStep })
        h.file("/screenshot.png", contentId = "content-shot", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        assertEquals(listOf(null, ThumbnailClassMinter.STEP_DOWN_QUALITIES[1]), h.previewQualities, "the default first, then one step")
        assertContentEquals(
            atStep,
            h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-shot", GetStoredThumbnail.CLASS_XLARGE))?.get(),
            "the step that fit is the class",
        )
        assertEquals(listOf(File.ThumbnailXLarge.id), h.emitter.calls.map { it.propertyId })
    }

    /** Every step over the cap is a decline — recorded once, after the last step, and never before it. */
    @Test
    fun aStillOverTheCapAtEveryQualityIsDeclinedAfterTheLastStep() = runTest {
        val h = harness(renderer = null, declines = setOf("/undecodable.jpg"), still = { ByteArray(UploadFileImplementation.MAX_XLARGE_THUMBNAIL_BYTES + 1) })
        h.file("/noise.png", contentId = "content-noise", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        assertEquals(ThumbnailClassMinter.STEP_DOWN_QUALITIES, h.previewQualities, "every step is tried, in order")
        assertEquals(
            ThumbnailClassBackfill.MINT_FORMAT,
            h.proxy.fileDatabase.readBytes(BackfillThumbnailClassesImplementation.thumbnailDeclinePath("content-noise", GetStoredThumbnail.CLASS_XLARGE))?.get()?.decodeToString(),
        )
        assertTrue(h.emitter.calls.isEmpty())

        // ⚠ A render that came back empty is the file saying no: no lower quality can change that.
        h.previewQualities.clear()
        h.file("/undecodable.jpg", contentId = "content-bad", local = true)
        h.run()
        assertEquals(listOf<Int?>(null), h.previewQualities, "an empty render is not stepped down")
    }

    @Test
    fun mintsTheClipForAVideoOnAPlatformWithARenderer_andNothingForAPhoto() = runTest {
        val renderer = RecordingRenderer { fakeClip }
        val h = harness(renderer)
        h.file("/clip.mp4", contentId = "content-clip", local = true)
        h.file("/still.jpg", contentId = "content-still", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("phone", setOf(GetStoredThumbnail.CLASS_MOTION))

        h.run()

        val clipPath = FileBytesPropertyWriter.thumbnailPath("content-clip", GetStoredThumbnail.CLASS_MOTION)
        assertContentEquals(fakeClip, h.proxy.fileDatabase.readBytes(clipPath)?.get())
        assertEquals(listOf(File.ThumbnailMotion.id), h.emitter.calls.map { it.propertyId })
        val call = renderer.calls.single()
        assertContentEquals(fileBytes, call.bytes, "the renderer is handed the file's own bytes")
        assertEquals(UploadFileImplementationCap.MOTION, call.maxBytes)
    }

    /** Every platform but Android: no renderer, so `MOTION` is skipped — and the marker still latches. */
    @Test
    fun withoutARendererTheClipIsSkippedNotFailed() = runTest {
        val h = harness(renderer = null)
        h.file("/clip.mp4", contentId = "content-clip", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("phone", setOf(GetStoredThumbnail.CLASS_MOTION, GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        assertEquals(listOf(File.ThumbnailXLarge.id), h.emitter.calls.map { it.propertyId }, "the poster still is minted, the clip is not this platform's")
        assertEquals(
            ThumbnailClassBackfill.markerFor(setOf(GetStoredThumbnail.CLASS_MOTION, GetStoredThumbnail.CLASS_XLARGE)),
            h.proxy.executionContext.readThumbnailBackfillMarker("device-1"),
        )
    }

    @Test
    fun aClassAlreadyPresentIsNotMintedAgain() = runTest {
        val h = harness(renderer = null)
        h.file("/a.jpg", contentId = "content-a", local = true)
        h.proxy.fileDatabase.writeByteArray(
            FileBytesPropertyWriter.thumbnailPath("content-a", GetStoredThumbnail.CLASS_XLARGE),
            byteArrayOf(9, 9, 9).toTByteArray(h.proxy.tContext),
        )
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        assertTrue(h.emitter.calls.isEmpty())
        assertContentEquals(
            byteArrayOf(9, 9, 9),
            h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-a", GetStoredThumbnail.CLASS_XLARGE))?.get(),
            "present means untouched",
        )
    }

    @Test
    fun aRealmThatWantsNothingMintsNothing_andLatchesForTheEmptySet() = runTest {
        val h = harness(renderer = null)
        h.file("/a.jpg", contentId = "content-a", local = true)

        h.run()

        assertTrue(h.emitter.calls.isEmpty())
        assertEquals(ThumbnailClassBackfill.markerFor(emptySet()), h.proxy.executionContext.readThumbnailBackfillMarker("device-1"))
    }

    /** No identity ⇒ no row to key a marker on ⇒ the op is a clean no-op (test / pre-sync wirings). */
    @Test
    fun withoutADeviceIdTheOpIsANoOp() = runTest {
        val h = harness(renderer = null, deviceId = null)
        h.file("/a.jpg", contentId = "content-a", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))

        h.run()

        assertTrue(h.emitter.calls.isEmpty())
    }

    // ---- harness ----

    private inner class Harness(
        val proxy: TestProxy,
        val emitter: RecordingContentEmitter,
        val store: InMemoryObjectStore,
    ) {
        /** How many times the (fake) `GetPreview` was asked to render — a render is the cost the decline record exists to avoid. */
        var previewCalls = 0

        /** The `GetPreview.Params.QUALITY` of each render, in order — `null` is the platform default. */
        val previewQualities = mutableListOf<Int?>()

        suspend fun file(path: String, contentId: String, local: Boolean) {
            val entity = Entity.create<File>(
                context = proxy.tContext,
                composite = File.create(proxy.tContext, path),
            ) {
                set(File.ContentId, contentId)
            }
            proxy.executionContext.saveData(entity)
            if (local) proxy.fileDatabase.writeByteArray(path, fileBytes.toTByteArray(proxy.tContext))
        }

        /** A file whose hero rung the keeper evicted at [stampedMs], with its bytes still here. */
        suspend fun evicted(path: String, contentId: String, stampedMs: Long) {
            file(path, contentId, local = true)
            proxy.fileDatabase.writeRawByteArray(
                ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
                stampedMs.toString().encodeToByteArray(),
            )
        }

        /** Another device in the realm wants the hero rung — what makes the pass do anything at all. */
        suspend fun wantHero() {
            proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))
        }

        suspend fun run() {
            proxy.executionContext.executeAsync<Data>(
                operationId = BackfillThumbnailClasses.ID,
                caller = null,
                params = emptyMap(),
            )
        }
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        renderer: MotionThumbnailRenderer?,
        deviceId: String? = "device-1",
        /** Set when the test needs a controllable now — the eviction sentinel's age is read from it. */
        clock: org.tenshu.core.time.Clock? = null,
        /** Paths the fake `GetPreview` declines (returns null for), as an undecodable file would. */
        declines: Set<String> = emptySet(),
        /** What the fake renders at each quality; the default is one small still at every quality. */
        still: (quality: Int?) -> ByteArray = { fakeStill },
    ): Harness {
        val emitter = RecordingContentEmitter()
        val store = InMemoryObjectStore()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = deviceId,
            clock = clock,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
            motionThumbnailRenderer = renderer,
            objectStore = store,
        )
        val harness = Harness(proxy, emitter, store)
        proxy.additionalImplementations[GetPreview.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                harness.previewCalls++
                val quality = (params?.get(GetPreview.Params.QUALITY) as? TInteger)?.get()?.toInt()
                harness.previewQualities += quality
                val path = (caller as? File)?.path?.get()
                @Suppress("UNCHECKED_CAST")
                return (if (path in declines) null else still(quality).toTByteArray(executionContext.context)) as T
            }
        }
        return harness
    }

    /**
     * ⚠⚠ **A class the keeper evicted is not minted again** (`drive-xxlarge-thumbnail-rung.md` §4.7).
     * Presence is a local stat, so without the sentinel an eviction reads as *never minted*: this
     * pass would re-render and re-emit the class on the next tick, refilling the cache the ceiling
     * just made room in — up to the whole budget, every tick, for as long as the library is over.
     */
    @Test
    fun aClassTheKeeperEvictedIsNotMintedAgain() = runTest {
        // ⚠ A *fresh* sentinel on a controlled clock: with a 1970 stamp this would pass through
        // the relay revalidation instead and pin nothing but the UNKNOWN default.
        val h = harness(renderer = null, clock = clock)
        h.evicted("/a.jpg", "content-a", stampedMs = clock.nowEpochMillis())
        h.wantHero()

        h.run()

        assertTrue(h.emitter.resolvabilityChecks.isEmpty(), "a young sentinel asks the relay nothing")

        assertEquals(0, h.previewCalls, "an evicted class is present, so nothing is rendered")
        assertTrue(h.emitter.calls.isEmpty(), "and nothing is published; got ${h.emitter.calls.size} emits")
        assertNull(
            h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-a", GetStoredThumbnail.CLASS_XLARGE)),
            "the blob stays gone — the point of evicting it",
        )
    }

    // ── The eviction sentinel is only believed while the relay still has the class ──

    /** ⚠ A fresh sentinel costs no relay traffic at all — the common case by far. */
    @Test
    fun aYoungEvictionSentinelIsBelievedWithoutAskingTheRelay() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.evicted("/a.jpg", "content-a", stampedMs = clock.nowEpochMillis())
        h.wantHero()

        h.run()

        assertTrue(h.emitter.resolvabilityChecks.isEmpty(), "nothing to ask: the sentinel is young")
        assertEquals(0, h.previewCalls, "and nothing is rendered")
    }

    @Test
    fun anOldSentinelWhoseClassTheRelayStillHasIsRenewedRatherThanReChecked() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.evicted("/a.jpg", "content-a", stampedMs = 1L)
        h.wantHero()
        h.emitter.resolvability = ContentResolvability.RESOLVABLE

        h.run()

        assertEquals(
            listOf(FileBytesPropertyWriter.derivedThumbnailContentId("content-a", GetStoredThumbnail.CLASS_XLARGE)),
            h.emitter.resolvabilityChecks,
            "⚠⚠ the rung's OWN content id: asking about the file's bytes would answer RESOLVABLE " +
                "forever for a thumbnail the relay actually lost",
        )
        assertEquals(0, h.previewCalls, "the relay still has it, so nothing is minted")

        h.emitter.resolvabilityChecks.clear()
        h.run()
        assertTrue(h.emitter.resolvabilityChecks.isEmpty(), "and the renewed stamp buys another interval")
    }

    /**
     * ⚠⚠ The case the whole revalidation exists for: this device deliberately dropped the class, so
     * nothing else would ever put it back — `ContentRepair` excludes thumbnails by design.
     */
    @Test
    fun aClassTheRelayLostIsMintedAgain() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.evicted("/a.jpg", "content-a", stampedMs = 1L)
        h.wantHero()
        h.emitter.resolvability = ContentResolvability.ABSENT

        h.run()

        assertEquals(1, h.previewCalls, "the relay lost it, so the byte holder mints it again")
        assertEquals(File.ThumbnailXLarge.id, h.emitter.calls.single().propertyId)
        assertNull(
            h.proxy.fileDatabase.readBytes(ThumbnailCacheSidecars.evictedPath("content-a", GetStoredThumbnail.CLASS_XLARGE)),
            "and the sentinel is gone, so the fresh blob is not treated as evicted",
        )
    }

    /** ⚠⚠ An offline device must not conclude the relay lost its library. */
    @Test
    fun aRelayThatCannotAnswerKeepsTheSentinel() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.evicted("/a.jpg", "content-a", stampedMs = 1L)
        h.wantHero()
        h.emitter.resolvability = ContentResolvability.UNKNOWN

        h.run()

        assertEquals(0, h.previewCalls, "unknown is not absent")
        assertNotNull(
            h.proxy.fileDatabase.readBytes(ThumbnailCacheSidecars.evictedPath("content-a", GetStoredThumbnail.CLASS_XLARGE)),
            "the sentinel stays, and the next pass asks again",
        )
    }

    /**
     * ⚠⚠ A device that cannot re-mint a class must keep its sentinel even on ABSENT: dropping it
     * loses the class's "present" mark and mints nothing, and it spends a revalidation slot to do it.
     * A desktop holding a `MOTION` clip it pulled but has no encoder for is exactly that device.
     */
    @Test
    fun aClassThisDeviceCannotMintKeepsItsSentinel() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.file("/clip.mp4", contentId = "content-clip", local = true)
        h.proxy.fileDatabase.writeRawByteArray(
            ThumbnailCacheSidecars.evictedPath("content-clip", GetStoredThumbnail.CLASS_MOTION),
            "1".encodeToByteArray(),
        )
        h.proxy.executionContext.writeWantedThumbnailClasses("phone", setOf(GetStoredThumbnail.CLASS_MOTION))
        h.emitter.resolvability = ContentResolvability.ABSENT

        h.run()

        assertTrue(h.emitter.resolvabilityChecks.isEmpty(), "no renderer here, so the relay is not even asked")
        assertNotNull(
            h.proxy.fileDatabase.readBytes(ThumbnailCacheSidecars.evictedPath("content-clip", GetStoredThumbnail.CLASS_MOTION)),
            "and the sentinel survives",
        )
    }

    /** ⚠ One pass must not turn a library of evicted classes into thousands of relay round trips. */
    @Test
    fun revalidationIsBoundedPerRun() = runTest {
        val h = harness(renderer = null, clock = clock)
        val files = BackfillThumbnailClassesImplementation.MAX_REVALIDATIONS_PER_RUN + 5
        repeat(files) { index ->
            h.evicted("/photo$index.jpg", "content-$index", stampedMs = 1L)
        }
        h.wantHero()
        h.emitter.resolvability = ContentResolvability.UNKNOWN

        h.run()

        assertEquals(
            BackfillThumbnailClassesImplementation.MAX_REVALIDATIONS_PER_RUN,
            h.emitter.resolvabilityChecks.size,
            "the rest keep their sentinels and wait for the next pass",
        )

        // ⚠⚠ And the budget is per **run**, not per instance. Production builds a fresh
        // implementation per dispatch, so only a reused one can pin the reset — and nothing stops a
        // future wiring from memoising it, at which point every sentinel past the first 50 would be
        // believed for good.
        h.proxy.additionalImplementations[BackfillThumbnailClasses.ID] = BackfillThumbnailClassesImplementation(
            fileDatabase = h.proxy.fileDatabase,
            objectStore = h.store,
            deviceId = "device-1",
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = h.emitter },
        )
        h.run()
        h.emitter.resolvabilityChecks.clear()

        h.run()

        assertEquals(
            BackfillThumbnailClassesImplementation.MAX_REVALIDATIONS_PER_RUN,
            h.emitter.resolvabilityChecks.size,
            "a reused implementation must start each run with a fresh budget",
        )
    }

    /**
     * ⚠⚠ Rendering into a closed gate fills the disk with blobs that can only wait in the journal or
     * be evicted — up to the whole budget, every tick the gate stays shut.
     */
    @Test
    fun nothingIsMintedWhileContentCannotBePublished() = runTest {
        val h = harness(renderer = null, clock = clock)
        h.file("/a.jpg", contentId = "content-a", local = true)
        h.wantHero()
        h.emitter.canPublish = false

        h.run()

        assertEquals(0, h.previewCalls, "not a single render while nothing can go out")
        assertTrue(h.emitter.calls.isEmpty())
        assertNull(
            h.proxy.executionContext.readThumbnailBackfillMarker("device-1"),
            "and the pass does not latch — it resumes when the gate opens",
        )
    }

    /**
     * ⚠⚠ The record is what makes a decline terminal. If the write fails and nothing says so, the
     * file is re-rendered every tick and charged to the budget each time — enough of them stall the
     * walk below its marker, silently, and it is reported as a complete run because a decline does
     * not withhold the marker. The log line is the failure's only symptom, so it is worth a test.
     */
    @Test
    fun aDeclineThatCannotBeRecordedIsNamed() = runTest {
        val h = harness(renderer = null, declines = setOf("/undecodable.jpg"))
        h.file("/undecodable.jpg", contentId = "content-bad", local = true)
        h.proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))
        val reports = mutableListOf<String>()
        h.proxy.additionalImplementations[BackfillThumbnailClasses.ID] = BackfillThumbnailClassesImplementation(
            fileDatabase = SentinelWritesFail(h.proxy.fileDatabase),
            objectStore = h.store,
            deviceId = "device-1",
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = h.emitter },
            report = { reports += it },
        )

        h.run()

        assertTrue(
            reports.any { "could NOT record the decline" in it && "content-bad" in it },
            "the failed record is named; got $reports",
        )
    }

    /** A [FileDatabase] whose sentinel writes fail and whose every other call is the real one. */
    private class SentinelWritesFail(private val delegate: FileDatabase) : FileDatabase by delegate {
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (filePath.endsWith(".declined")) error("no space left on device")
            delegate.writeRawByteArray(filePath, bytes)
        }
    }

    private class RenderCall(val bytes: ByteArray, val maxBytes: Int)

    private class RecordingRenderer(private val result: () -> ByteArray?) : MotionThumbnailRenderer {
        val calls = mutableListOf<RenderCall>()
        override fun render(videoBytes: ByteArray, maxBytes: Int): ByteArray? {
            calls += RenderCall(videoBytes, maxBytes)
            return result()
        }
    }

    private class EmitCall(val entityId: String, val propertyId: String, val filePath: String)

    private class RecordingContentEmitter : FileContentSyncEmitter {
        val calls = mutableListOf<EmitCall>()

        /** What the relay says about a rung the keeper evicted, and how often it was asked. */
        var resolvability = ContentResolvability.UNKNOWN
        val resolvabilityChecks = mutableListOf<String>()

        /** Whether content can go out at all right now (the metered gate / the quota latch). */
        var canPublish = true

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            calls += EmitCall((targetEntityId as DataId.StringDataId).keyValue.get(), propertyId, filePath)
            return true
        }

        override suspend fun contentResolvability(targetEntityId: DataId, contentId: String): ContentResolvability {
            resolvabilityChecks += contentId
            return resolvability
        }

        override suspend fun canPublishContentNow(): Boolean = canPublish

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true
    }

    /** The upload's caps, by name — `UploadFileImplementation`'s companion is `internal` to the module. */
    private object UploadFileImplementationCap {
        val MOTION = org.tenshu.file.impl.UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES
    }
}
