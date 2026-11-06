package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Size
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.impl.BackfillThumbnailClassesImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The order of `EnsureThumbnailClass`'s gates is the design** (`drive-xxlarge-thumbnail-rung.md`
 * §4.6, X-4), and every one of them guards the same thing: a **6–18 MB download of the original**.
 * A gate that silently stopped working would not fail anything — the picture would still appear,
 * having cost a user on cellular their data or a user at quota a refused upload. So each is asserted
 * by what it *prevents*, not only by what it returns.
 *
 * ⚠ `downloads` counts resolutions of `File.Data` specifically. That is the expensive call, and it
 * is the one every gate above step 6 exists to avoid.
 */
class EnsureThumbnailClassGatesTest {

    private val photo = "/Backup/Phone/Camera/p1.jpg"
    private val contentId = "content-p1"
    private val renderedRung = ByteArray(2_048) { (it % 251).toByte() }

    @Test
    fun aClassAlreadyOnDiskIsReturnedWithoutTouchingTheNetwork() = runTest {
        val h = harness()
        h.writeRung(renderedRung)

        val bytes = h.ensure()

        assertNotNull(bytes, "a stored class must answer")
        assertEquals(0, h.resolver.calls.size, "nothing may be resolved when the class is already here")
        assertEquals(0, h.previewCalls, "and nothing may be rendered")
    }

    @Test
    fun aClassThisDeviceAlreadyDeclinedIsNotRetried() = runTest {
        val h = harness()
        h.writeDeclineSentinel()

        assertNull(h.ensure(), "a declined class stays declined until the quality changes")
        assertEquals(0, h.resolver.calls.size, "and it costs no network at all")
        assertEquals(0, h.previewCalls)
    }

    /**
     * ⚠⚠ **The relay must have *answered* before a download is licensed.** This is the gate that
     * replaced a check which could not fire: `OnDemandResolution.resolve` returns `Success` for a
     * content-addressed thumbnail whatever happened, because `getChunk` collapses "missing" and
     * "the request failed" into one null. So an unreachable relay looked exactly like "nobody ever
     * minted this", and every dwell during an outage would have spent a whole original proving it.
     */
    @Test
    fun anUnreachableRelayIsNotAnAbsence() = runTest {
        for (answer in listOf(ContentResolvability.UNKNOWN, ContentResolvability.RESOLVABLE)) {
            val h = harness()
            h.emitter.resolvability = answer

            assertNull(h.ensure(), "$answer is not a confirmed absence")
            assertEquals(0, h.downloads, "and must not download the original: $answer")
            assertEquals(0, h.previewCalls)
        }
    }

    /**
     * ⚠⚠ The **fail-closed** half of the same gate. With no emitter there is nothing that can say
     * whether the relay has the rung, and "cannot tell" must not license a whole-original download —
     * the opposite choice from `MeteredNetworkSignal`, which fails *open* because refusing there
     * costs a picture rather than a user's data.
     */
    @Test
    fun noEmitterMeansNoMint() = runTest {
        val h = harness(emitter = null)

        assertNull(h.ensure(), "without an emitter nothing can confirm an absence")
        assertEquals(0, h.downloads)
        assertEquals(0, h.previewCalls)
    }

    @Test
    fun aRungAnotherDevicePublishedIsServedWithoutMinting() = runTest {
        val h = harness()
        // The pull lands it, as a real resolver would.
        h.resolver.onResolve = { propertyId -> if (propertyId == File.ThumbnailXXLarge.id) h.writeRung(renderedRung) }

        assertNotNull(h.ensure(), "a published rung is the cheap answer")
        assertEquals(0, h.downloads, "pulling half a megabyte beats downloading the whole file")
        assertEquals(0, h.previewCalls)
    }

    @Test
    fun noRoomUnderQuotaDownloadsNothing() = runTest {
        val h = harness(quotaFull = true)

        assertNull(h.ensure(), "at quota the relay would refuse the rung anyway")
        assertEquals(0, h.downloads, "so the original must not be downloaded to make it")
    }

    @Test
    fun aMeteredNetworkRefusesUntilTheUserAsksForThisPicture() = runTest {
        val h = harness(metered = true)

        assertNull(h.ensure(), "a dwell is not consent to spend cellular data on an original")
        assertEquals(0, h.downloads)

        // ⚠ The override is the *only* thing that lifts it — and it lifts nothing else.
        assertNotNull(h.ensure(userRequested = true), "an explicit ask may spend it")
        assertEquals(1, h.downloads)
    }

    /**
     * ⚠⚠ The gates above bound the **rung** (1 MiB against the relay's quota) and the network it
     * travels over. Neither bounds the **download**, and `canCarry` admits video and PDF for every
     * still rung — so before this gate a one-second look at a 4K clip fetched the whole clip to
     * render a poster frame. The doc's "6–18 MB" described phone photographs; nothing enforced it.
     */
    @Test
    fun anOriginalTooLargeToBeAPhotographIsNeverFetched() = runTest {
        val h = harness(originalBytes = 780L * 1024 * 1024)

        assertNull(h.ensure(), "a 780 MB video is not a photograph a glance may download")
        assertEquals(0, h.downloads)
        assertEquals(0, h.previewCalls)
    }

    /** ⚠ A row that does not say how big it is counts as too big — a guess is an unbounded fetch. */
    @Test
    fun anUnknownSizeIsTreatedAsTooLarge() = runTest {
        val h = harness(originalBytes = null)

        assertNull(h.ensure())
        assertEquals(0, h.downloads)
    }

    /**
     * ⚠⚠ **The size gate bounds the transfer, not the render** — so it is asked only when a transfer
     * would actually happen. A 780 MB scan this device already holds costs nothing to fetch, and
     * refusing it would turn a download limit into a limit on what may be *rendered from*, which is
     * not what the number means.
     *
     * The same 780 MB that [anOriginalTooLargeToBeAPhotographIsNeverFetched] refuses, minted here
     * purely because the bytes are already on disk. Deleting the `fileSize(filePath) == null`
     * condition makes this one red and that one stay green.
     */
    @Test
    fun theSizeGateBoundsTheTransferNotTheRender() = runTest {
        val h = harness(originalBytes = 780L * 1024 * 1024, alreadyLocal = true)

        assertNotNull(h.ensure(), "a local original is rendered whatever it weighs")
        assertEquals(0, h.downloads, "and nothing was transferred to do it")
        assertEquals(1, h.previewCalls)
    }

    /**
     * ⚠ The anti-vacuity half of the pair above: with the bytes local, the gate is *skipped*, not
     * satisfied. An unknown size — which [anUnknownSizeIsTreatedAsTooLarge] refuses — also mints
     * here, because the question was never asked.
     */
    @Test
    fun aLocalOriginalOfUnknownSizeStillMints() = runTest {
        val h = harness(originalBytes = null, alreadyLocal = true)

        assertNotNull(h.ensure(), "no transfer to bound ⇒ no size to know")
        assertEquals(0, h.downloads)
    }

    @Test
    fun theMintStoresAndPublishesTheRung() = runTest {
        val h = harness()

        val bytes = assertNotNull(h.ensure(), "the whole point: a rung where there was none")
        assertEquals(renderedRung.size, bytes.get().size)
        assertEquals(1, h.downloads, "the original is downloaded exactly once")
        assertNotNull(
            h.proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XXLARGE)),
            "the rung must be on disk",
        )
        assertTrue(
            h.emitted.contains(File.ThumbnailXXLarge.id),
            "and published inline — D-19; `ThumbnailClassMinter.store` is write + emit in one",
        )
    }

    // ---- harness ----

    private class FakeResolver : OnDemandResolution {
        val calls = mutableListOf<String>()
        var onResolve: (String) -> Unit = {}

        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?
        ): TResult<Data?> {
            calls += propertyId
            onResolve(propertyId)
            return TResult.Success.create(executionContext.context, null)
        }
    }

    private class Recording : FileContentSyncEmitter {
        val propertyIds = mutableListOf<String>()

        /**
         * ⚠⚠ What the relay says about the rung. ABSENT is the realistic default for this op's
         * reason to exist — nobody ever minted it — and the other two are what a *failed* read looks
         * like, which must never license a download.
         */
        var resolvability: ContentResolvability = ContentResolvability.ABSENT

        override suspend fun contentResolvability(targetEntityId: DataId, contentId: String) = resolvability
        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            propertyIds += propertyId
            return true
        }
        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = true
    }

    private inner class Harness(
        val proxy: TestProxy,
        val resolver: FakeResolver,
        val emitter: Recording,
    ) {
        var previewCalls = 0
        val downloads: Int get() = resolver.calls.count { it == File.Data.id }
        val emitted: List<String> get() = emitter.propertyIds

        fun writeRung(bytes: ByteArray) {
            proxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XXLARGE),
                bytes,
            )
        }

        fun writeDeclineSentinel() {
            proxy.fileDatabase.writeRawByteArray(
                BackfillThumbnailClassesImplementation.thumbnailDeclinePath(contentId, GetStoredThumbnail.CLASS_XXLARGE),
                ThumbnailClassBackfill.MINT_FORMAT.encodeToByteArray(),
            )
        }

        suspend fun ensure(userRequested: Boolean = false) =
            proxy.executionContext.executeAsync<org.tenshu.core.TByteArray?>(
                operationId = EnsureThumbnailClass.ID,
                caller = null,
                params = buildMap {
                    put(EnsureThumbnailClass.Params.PATH, org.tenshu.core.file.FilePath.create(proxy.tContext, photo))
                    put(EnsureThumbnailClass.Params.THUMBNAIL_CLASS, GetStoredThumbnail.CLASS_XXLARGE.toTString(proxy.tContext))
                    if (userRequested) {
                        put(EnsureThumbnailClass.Params.USER_REQUESTED, true.toTBoolean(proxy.tContext))
                    }
                },
            )
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        metered: Boolean = false,
        emitter: Recording? = Recording(),
        quotaFull: Boolean = false,
        originalBytes: Long? = 6L * 1024 * 1024,
        /** Seeds the original's bytes on disk, so no transfer would happen — see [theSizeGateBoundsTheTransferNotTheRender]. */
        alreadyLocal: Boolean = false,
    ): Harness {
        val resolver = FakeResolver()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            onDemandResolution = resolver,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
            meteredNetwork = { metered },
            storageCapacityHolder = if (quotaFull) fullQuota() else null,
        )
        val harness = Harness(proxy, resolver, emitter ?: Recording())

        // The row, its content id, and the original's bytes once a "download" lands them.
        val entity = Entity.create<File>(
            context = proxy.tContext,
            composite = File.create(proxy.tContext, photo),
        ) {
            set(File.ContentId, contentId)
            originalBytes?.let { set(File.Size, it) }
        }
        proxy.executionContext.saveData(entity)
        if (alreadyLocal) proxy.fileDatabase.writeRawByteArray(photo, ByteArray(6_000) { 7 })
        resolver.onResolve = { propertyId ->
            if (propertyId == File.Data.id) {
                proxy.fileDatabase.writeRawByteArray(photo, ByteArray(6_000) { 7 })
            }
        }

        proxy.additionalImplementations[GetPreview.ID] = object : SyncOperationImplementation {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                harness.previewCalls++
                return renderedRung.toTByteArray(executionContext.context) as T
            }
        }
        return harness
    }

    /** A holder whose figures leave no room, so `ensureWithinQuota` throws. */
    private fun fullQuota(): StorageCapacityHolder = StorageCapacityHolder().apply {
        figures = org.tenshu.storage.proxy.StorageFigures(
            hasStorage = true,
            usedBytes = 1_000L,
            limitBytes = 1_000L,
        )
    }
}
