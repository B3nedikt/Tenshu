package org.tenshu.systemtest

import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.timeline.data.TimelineProjectionRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.number.TInteger
import org.tenshu.core.file.GetPreview
import org.tenshu.core.collection.list.relations
import org.tenshu.core.relation.eager
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.get
import org.tenshu.core.file.File
import org.tenshu.core.file.Preview
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList

/**
 * Acceptance test for docs/proposals/drive-synced-thumbnails.md Phase 1: the
 * `File.Thumbnail` stored value, generated on the uploading device from its local
 * bytes, must reach a paired device that holds **no** bytes — closing the reported
 * "preview shows on desktop but never on the phone" gap.
 *
 * This is the test single-device unit tests structurally cannot replace: the host
 * holds the bytes (so it could always render a preview), the joiner does not, so a
 * non-null preview on the joiner can *only* come from the synced thumbnail. It
 * exercises the load-bearing claim of the design — that a `TByteArray` value
 * property `set` on the entity rides `File`'s eager block and is projectable on the
 * receiver, exactly as `File.Size` is.
 */
class SyncedThumbnailSystemTest {

    /**
     * ⚠ Deterministic only while every object-store access on both devices is made under the
     * `DatabaseLock`: the store is a plain map, so one unlocked writer can make a row that exists
     * read as absent to whichever op is looking — here the host's upload, refused for want of a
     * `/` that was there. The harness's store records any such access and `system.shutdown()`
     * fails on it (`LockCheckedObjectStore`), which is what keeps that from being a one-in-a-
     * thousand failure of this test.
     */
    @Test fun hostUploadsImage_joinerSeesSyncedThumbnailWithoutBytes() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner, path) = hostWithPhotoAndPairedJoiner(system)

            // The preview must resolve on the joiner via the same projection the Files
            // list uses (`getFilesPaged`). Since Phase 1b, `File.Preview` is the single
            // "best available preview" API: the joiner holds no bytes, so the
            // `GetPreview` resolver cannot compute a preview and instead falls back to
            // the stored, eagerly-synced `File.Thumbnail`. A non-null `File.Preview` on
            // the joiner can therefore *only* come from the synced thumbnail — the same
            // acceptance intent as before, now asserted through the consumer-facing API.
            // It is read through `select` / `relations`, not `entity.get`. Allow a
            // couple of poll cycles for the value to settle.
            // ⚠⚠ C7 changed this contract and the change is pinned here rather than
            // hidden: both thumbnail classes are `Syncable(OnDemand)` now, so projecting
            // `File.Preview` alone on a cold joiner resolves to null forever. The caller
            // must fetch the thumbnails first — see [SyncStrategy.OnDemand], "reading one",
            // for why, and `TenshuFilesRepository.getPreview` for the same two-step.
            var joinerPreview: ByteArray? = null
            system.advanceUntil(timeout = 20.seconds) {
                joiner.tenshuClient.fileService.defaultFileSystem.root
                    .files
                    .search<File> { this.path equalTo path }
                    .select { relations(File.Thumbnail, File.ThumbnailLarge) }
                    .resolve()

                val resolved = joiner.tenshuClient.fileService.defaultFileSystem.root
                    .files
                    .search<File> { this.path equalTo path }
                    // eager(...): File.Preview is deferred-by-default; this one-shot
                    // read wants the resolved bytes inline (core-deferred-properties.md P4).
                    .select { relations(eager(File.Preview(width = 100, height = 100))) }
                    .resolve()
                joinerPreview = resolved.getOrNull()?.get(File.Preview)?.getOrNull()
                joinerPreview != null
            }

            assertNotNull(
                joinerPreview,
                "Joiner must surface a preview via File.Preview (resolved from the " +
                    "synced File.Thumbnail) without holding the file's bytes",
            )
            assertTrue(joinerPreview!!.isNotEmpty(), "Resolved preview must carry bytes")

            // Phase 3 (drive-synced-thumbnails.md §10.2): the host generates two
            // synced classes — LIST (100 px PNG) and LARGE (256 px JPEG). On the
            // byte-less joiner, a *large* File.Preview request (480, as the file
            // details screen issues) must resolve the LARGE class (sharp), while the
            // 100×100 list request resolves the LIST class — both through the single
            // File.Preview API. We assert both resolve to non-empty bytes; the LARGE
            // request carries JPEG (FF D8), proving it came from the LARGE class and
            // not an upscaled LIST PNG.
            var joinerLargePreview: ByteArray? = null
            system.advanceUntil(timeout = 20.seconds) {
                val resolved = joiner.tenshuClient.fileService.defaultFileSystem.root
                    .files
                    .search<File> { this.path equalTo path }
                    .select { relations(eager(File.Preview(width = 480, height = 480))) }
                    .resolve()
                joinerLargePreview = resolved.getOrNull()?.get(File.Preview)?.getOrNull()
                joinerLargePreview != null
            }
            assertNotNull(
                joinerLargePreview,
                "Joiner must surface the LARGE class via a large File.Preview request",
            )
            assertTrue(joinerLargePreview!!.size >= 2, "LARGE preview must carry bytes")
            assertTrue(
                (joinerLargePreview!![0].toInt() and 0xFF) == 0xFF &&
                    (joinerLargePreview!![1].toInt() and 0xFF) == 0xD8,
                "The large request must resolve the JPEG-encoded LARGE class (FF D8), " +
                    "not an upscaled LIST PNG",
            )

            // §12 T-1's other half — the one that had never been asserted, and so had never
            // shipped. The hero class is `Syncable(OnDemand)` like the other two, and `GetPreview`
            // resolves stored classes *locally*: it never fetches. So a joiner sees `XLARGE` only
            // if some caller pulled it by name, and until `TenshuPhotosRepository.cacheThumbnails`
            // did, every hero on every non-uploading device was the 256 px class upscaled.
            //
            // ⚠ Asserted by decoded *dimensions*, not by presence. Both rungs are JPEG and both
            // are non-empty, so every cheaper assertion available here - non-null, non-empty, the
            // FF D8 magic above - passes just as happily on the blurry answer. The pixel count is
            // the only thing that can tell the two apart, which is precisely why its absence was
            // survivable for so long.
            val heroBefore = joinerPreviewEdge(joiner, path, requestPx = HERO_REQUEST_PX)
            assertNotNull(heroBefore, "A hero-sized request must resolve to something")
            assertTrue(
                heroBefore <= GetStoredThumbnail.LARGE_PX,
                "Precondition: with only LIST+LARGE pulled, a $HERO_REQUEST_PX px request can " +
                    "only be served by the ${GetStoredThumbnail.LARGE_PX} px class; was $heroBefore px",
            )

            var heroAfter: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                joiner.tenshuClient.fileService.defaultFileSystem.root
                    .files
                    .search<File> { this.path equalTo path }
                    .select { relations(File.Thumbnail, File.ThumbnailLarge, File.ThumbnailXLarge) }
                    .resolve()

                heroAfter = joinerPreviewEdge(joiner, path, requestPx = HERO_REQUEST_PX)
                heroAfter != null && heroAfter!! > GetStoredThumbnail.LARGE_PX
            }
            assertNotNull(heroAfter, "A hero-sized request must resolve after the XLARGE pull")
            assertTrue(
                heroAfter!! > GetStoredThumbnail.LARGE_PX,
                "Once XLARGE is pulled, a $HERO_REQUEST_PX px request must be served by the hero " +
                    "class - expected more than ${GetStoredThumbnail.LARGE_PX} px, was $heroAfter px",
            )

            // The other half of this test's name, which nothing above asserts: every preview the
            // joiner drew came from a thumbnail class, and none of them cost it the original.
            // ⚠ Read where an on-demand `File.Data` pull lands (`FileBytesOnDemandSystemTest`), and
            // read on the host first — the same question must be answerable "yes" somewhere, or a
            // null here would only mean the path is wrong.
            assertNotNull(
                host.fileDatabase.readBytes(path),
                "Premise: the uploading device holds the original at $path",
            )
            assertNull(
                joiner.fileDatabase.readBytes(path),
                "The joiner must have drawn every preview above without fetching the original; it " +
                    "holds ${joiner.fileDatabase.fileSize(path)} bytes at $path",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * The host's upload, as a paired joiner sees it: host and joiner in one realm, a real 2600×1950
     * PNG uploaded on the host, and its `File` row arrived on the joiner — which holds no bytes.
     *
     * ⚠⚠ **Larger than `XXLARGE_PX` on purpose** (`drive-xxlarge-thumbnail-rung.md` X-1 review). At
     * 1600 px the source was smaller than the top rung, so that rung's blob was a 1600 px copy of the
     * original and every assertion below read 1600 whether it was served the rung or a render — the
     * byte-holder guards passed without being able to fail. A source above 2048 px makes the two
     * answers different sizes, which is the only thing that makes them assertions.
     */
    private data class HostWithPhoto(val host: TestClient, val joiner: TestClient, val path: String)

    private suspend fun hostWithPhotoAndPairedJoiner(
        system: TestSystem,
        photo: ByteArray = pngBytes(width = 2600, height = 1950),
        hostOperationDecorator: (String, OperationImplementation) -> OperationImplementation = { _, impl -> impl },
    ): HostWithPhoto {
        val host = system.client(
            deviceId = "host",
            role = TestClientRole.RealmHost,
            pollingIntervalMs = 1_000,
            operationDecorator = hostOperationDecorator,
        )
        val joiner = system.client(
            deviceId = "joiner",
            role = TestClientRole.JoiningDevice,
            pollingIntervalMs = 1_000
        )

        host.bootstrapAsHost(deviceName = "Host Desktop")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 30.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            status is TResult.Success && status.value?.get() == true
        }

        // A real, decodable PNG so the host's GetPreview renders an actual
        // thumbnail (an arbitrary byte blob would decode to null → no thumbnail).
        val fileName = "photo.png"
        val path = "/$fileName"
        host.tenshuClient.fileService.defaultFileSystem
            .uploadFile("/", fileName, photo)
            .valueOrFail("host uploadFile($path)")

        // The entity propagates first (File is Syncable(Eager)).
        system.advanceUntil(timeout = 20.seconds) {
            val dataId = DataId.StringDataId.create(type = File.ID, keyValue = path)
            joiner.executionContext.database.getData(dataId) != null
        }

        return HostWithPhoto(host, joiner, path)
    }

    /**
     * ⚠⚠ **The Photos journey, through the real `TenshuPhotosRepository` — not a copy of its pulls.**
     * The test above pulls the hero rung by hand, so it proved the rung *could* reach a joiner while
     * the repository never asked for it: `preview` pulled only on a null read, the grid's tile read
     * had already pulled `LARGE`, and so a viewer page on every non-uploading device was the 256 px
     * class upscaled (`drive-synced-thumbnails.md` §16). The grid runs first here because it runs
     * first on a device, and that ordering is the whole defect.
     *
     * ⚠ Asserted by decoded dimensions, for the reason [joinerPreviewEdge] gives.
     */
    @Test fun joinerOpensAPhotoAfterTheGrid_andIsServedTheHeroRung() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner, path) = hostWithPhotoAndPairedJoiner(system)
            val joinerPhotos = TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test: no reachability engine */)

            var tileEdge: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                tileEdge = joinerPhotos.preview(path, GRID_TILE_PX, GRID_TILE_PX)?.bytes?.let(::decodedEdge)
                tileEdge != null
            }
            assertEquals(
                GetStoredThumbnail.LARGE_PX,
                tileEdge,
                "Precondition: the grid's tile read leaves the joiner holding the LARGE class",
            )

            var heroEdge: Int? = null
            var heroStandIn: Boolean? = null
            try {
                system.advanceUntil(timeout = 20.seconds) {
                    val hero = joinerPhotos.preview(path, HERO_REQUEST_PX, HERO_REQUEST_PX)
                    heroEdge = hero?.bytes?.let(::decodedEdge)
                    heroStandIn = hero?.standIn
                    (heroEdge ?: 0) > GetStoredThumbnail.LARGE_PX
                }
            } catch (e: TimeoutCancellationException) {
                fail(
                    "A $HERO_REQUEST_PX px preview on a joiner that already holds LARGE must be " +
                        "served by the hero rung; it stayed at $heroEdge px",
                )
            }
            assertEquals(GetStoredThumbnail.XLARGE_PX, heroEdge)
            assertEquals(false, heroStandIn, "The hero rung is the answer, not a stand-in for one")

            // ⚠⚠ **The joiner's progressive read, end to end** — the half the byte-holder assertions
            // below cannot reach (`wantsRung` is false there, so the flow emits once by
            // construction). A device without the original opens the photograph at a retina size:
            // it draws the best class it already has, then the named rung once the pull lands.
            //
            // ⚠ The **last** emission is what matters. An earlier version of `viewerPreview` emitted
            // whatever `heroRung` pulled rather than re-asking the ladder, so the fallback arm could
            // replace a picture with a smaller one — this is where that shows up as a failure.
            // ⚠ Retried, like every other content-plane read in this file. A rung whose manifest
            // has not landed yet resolves to `Success(null)` — no error, no repair, no retry on the
            // path (`OnDemandResolutionImpl`, and `ContentRepair` deliberately excludes thumbnails)
            // — so an unretried read fails with a message about what the viewer drew rather than
            // about what had not arrived.
            var joinerEdges: List<Int?> = emptyList()
            system.advanceUntil(timeout = 30.seconds) {
                joinerEdges = TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test: no reachability engine */)
                    .viewerPreview(path, 3_072)
                    .toList()
                    .map { decodedEdge(it.bytes) }
                joinerEdges.lastOrNull() == GetStoredThumbnail.XXLARGE_PX
            }
            assertEquals(
                GetStoredThumbnail.XXLARGE_PX,
                joinerEdges.last(),
                "a byte-less viewer ends on the largest rung it could pull: $joinerEdges",
            )
            // ⚠ Not "exactly two": one emission is the correct outcome once the rung is already
            // local, which is what the retry above converges to. What must hold either way is that
            // the viewer never goes backwards.
            assertTrue(
                joinerEdges.zipWithNext().all { (before, after) -> (after ?: 0) >= (before ?: 0) },
                "no emission may be smaller than the one it replaces: $joinerEdges",
            )

            // ⚠⚠ **And the same at the hero floor, which is the case that actually regressed.** The
            // assertion above asks at 3072 px, so the pull takes the `XXLARGE` arm and succeeds —
            // it never enters the `XLARGE` fallback, and therefore cannot see a downgrade that only
            // the fallback produces. A review proved it green against the unfixed code.
            //
            // The discriminating state is a device that has `XXLARGE` and **not** `XLARGE`, asking
            // at exactly `XLARGE_PX`. A second photograph gives it: the joiner's first read of it is
            // a retina one, which pulls the top rung and nothing else.
            val second = "/second.png"
            host.tenshuClient.fileService.defaultFileSystem
                .uploadFile("/", "second.png", pngBytes(width = 2600, height = 1950))
                .valueOrFail("host uploadFile($second)")
            system.advanceUntil(timeout = 20.seconds) {
                joiner.executionContext.database
                    .getData(DataId.StringDataId.create(type = File.ID, keyValue = second)) != null
            }
            val secondReads = TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test: no reachability engine */)
            system.advanceUntil(timeout = 30.seconds) {
                secondReads.viewerPreview(second, 3_072).toList()
                    .map { decodedEdge(it.bytes) }
                    .lastOrNull() == GetStoredThumbnail.XXLARGE_PX
            }

            val atTheFloor = secondReads.viewerPreview(second, GetStoredThumbnail.XLARGE_PX)
                .toList()
                .map { decodedEdge(it.bytes) }
            assertTrue(
                atTheFloor.zipWithNext().all { (before, after) -> (after ?: 0) >= (before ?: 0) },
                "a hero-floor read must never replace a picture with a smaller one: $atTheFloor",
            )

            // ⚠ And the device holding the original is not sent down the joiner's path: it answers
            // the same request sharp without asking the content plane for anything.
            val hostEdge = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test: no reachability engine */)
                .preview(path, HERO_REQUEST_PX, HERO_REQUEST_PX)
                ?.bytes
                ?.let(::decodedEdge)
            assertEquals(GetStoredThumbnail.XLARGE_PX, hostEdge, "The uploading device's own hero read")

            // ⚠⚠ **The two surfaces answer differently on a byte holder, and that is the point of
            // D-17** (`drive-xxlarge-thumbnail-rung.md` §4.2). Both asserted on the decoded bitmap,
            // on the device that holds a 2600 px original.
            //
            // `preview` is the feed's read: above the top rung it takes `XXLARGE` stretched within
            // `COVER_TOLERANCE`, because paying a whole-file read per tile is what the tolerance
            // exists to avoid.
            val hostFeedEdge = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test: no reachability engine */)
                .preview(path, 3_072, 3_072)
                ?.bytes
                ?.let(::decodedEdge)
            assertEquals(
                GetStoredThumbnail.XXLARGE_PX,
                hostFeedEdge,
                "A feed read takes the stretched rung rather than read the whole file",
            )

            // `viewerPreview` is the one photograph on screen: it refuses the stretch and renders the
            // original at the size asked for, capped at the source's own 2600 px. ⚠ This is the X-1
            // review's finding 1 as an assertion — read 2048 here and the device with the best
            // possible source is drawing its own photograph upscaled from a rung.
            val hostViewerEdges = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test: no reachability engine */)
                .viewerPreview(path, 3_072)
                .toList()
                .map { decodedEdge(it.bytes) }
            assertEquals(
                listOf(2_600),
                hostViewerEdges,
                "The byte holder renders, once — it has nothing to upgrade to",
            )

            // ⚠⚠ Between the hero rung and the top rung D-17 takes the **covering** rung and does not
            // render: at 1536 px the stored 2048 px class is a downscale of the same picture, and
            // reading a whole file to beat it is the "always the original" rule D-17 did not choose.
            val hostMidEdges = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test: no reachability engine */)
                .viewerPreview(path, 1_536)
                .toList()
                .map { decodedEdge(it.bytes) }
            assertEquals(
                listOf(GetStoredThumbnail.XXLARGE_PX),
                hostMidEdges,
                "A covering rung still wins under D-17 — only the stretch is refused",
            )

        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ The fallback, and why it is marked. A photograph with no hero rung anywhere still gets the
     * best class the joiner has — but as a *stand-in*, because a grid that cached it under the
     * hero-sized key would keep the tile blurry after a backfill minted the real one (§16).
     *
     * Made deterministic with a host whose `GetPreview` will not render the hero size — the upload
     * that predates T-1, which no backfill has reached. ⚠ This used to be a noise image over the
     * 256 KB cap, and §17 made that impossible: the mint steps quality down and the cap is 512 KB,
     * so even pure noise fits (1024 px at q50 measured 420 KB). The first assertion pins the
     * premise, so a mint that finds another way to the hero rung fails here rather than turning this
     * into a test of the happy path.
     */
    @Test fun joinerAsksForAHeroRungNobodyMinted_andIsServedAMarkedStandIn() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner, path) = hostWithPhotoAndPairedJoiner(
                system,
                hostOperationDecorator = { type, impl ->
                    if (type == GetPreview.ID) NeverRendersTheHeroSize(impl as SyncOperationImplementation) else impl
                },
            )
            val hostXLarge = host.tenshuClient.fileService.defaultFileSystem.root
                .files
                .search<File> { this.path equalTo path }
                .select { relations(File.ThumbnailXLarge) }
                .resolve()
                .getOrNull()
                ?.get(File.ThumbnailXLarge)
                ?.getOrNull()
            assertNull(hostXLarge, "Premise: a host that never rendered the hero size published no hero rung")

            val joinerPhotos = TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test: no reachability engine */)
            system.advanceUntil(timeout = 20.seconds) {
                joinerPhotos.preview(path, GRID_TILE_PX, GRID_TILE_PX) != null
            }

            val hero = joinerPhotos.preview(path, HERO_REQUEST_PX, HERO_REQUEST_PX)
            assertNotNull(hero, "A photograph with LARGE but no hero rung still has something to draw")
            assertEquals(GetStoredThumbnail.LARGE_PX, decodedEdge(hero.bytes), "It is the LARGE class")
            assertTrue(hero.standIn, "…and it says it is standing in for the hero rung")
        } finally {
            system.shutdown()
        }
    }

    private fun decodedEdge(bytes: ByteArray): Int? =
        ImageIO.read(ByteArrayInputStream(bytes))?.let { maxOf(it.width, it.height) }

    /**
     * The longest edge of what `File.Preview` actually resolves to at [requestPx], or null.
     *
     * ⚠ Decoding is the point: the rung a request lands on is invisible in the byte array's
     * length or magic number, and both candidate rungs here are non-empty JPEGs.
     */
    private suspend fun joinerPreviewEdge(
        joiner: TestClient,
        path: String,
        requestPx: Int,
    ): Int? {
        val bytes = joiner.tenshuClient.fileService.defaultFileSystem.root
            .files
            .search<File> { this.path equalTo path }
            .select { relations(eager(File.Preview(width = requestPx, height = requestPx))) }
            .resolve()
            .getOrNull()
            ?.get(File.Preview)
            ?.getOrNull()
            ?: return null
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return maxOf(image.width, image.height)
    }

    /** A host `GetPreview` that renders every size but the hero rung's, as a pre-T-1 upload did. */
    private class NeverRendersTheHeroSize(private val delegate: SyncOperationImplementation) : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            val mint = (params?.get(GetPreview.Params.GENERATE_FRESH) as? TBoolean)?.get() == true
            val edge = (params?.get(GetPreview.Params.WIDTH) as? TInteger)?.get()?.toInt()
            @Suppress("UNCHECKED_CAST")
            if (mint && edge == GetStoredThumbnail.XLARGE_PX) return null as T
            return delegate.execute(executionContext, operationContext, implementationFactory, caller, params)
        }
    }

    /** A minimal valid PNG of the given dimensions, encoded via ImageIO. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                // A simple gradient — non-uniform so the encoder produces real content.
                image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x40)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * ⚠⚠ **Timeline's moment hero, which carried §16.2's defect long after Photos was fixed**
     * (`drive-xxlarge-thumbnail-rung.md` §4.4, X-6). `TimelineProjectionRepository.photoBytes`
     * pulled the hero rung inside `cachePhotoThumbnails`, which it called **only when
     * `readPhotoPreview` came back null** — and on a joiner it is not null by the time a moment
     * opens, because the feed has already pulled `LIST` + `LARGE`. So a 1600 px hero was answered
     * by the 256 px class, a ~6x upscale, while the rung sat on the content plane.
     *
     * ⚠ The precondition is the whole point, exactly as in the Photos case above: a tile-sized read
     * first, so the joiner *holds* `LARGE` and the hero read cannot succeed by accident.
     *
     * ⚠⚠ And it asserts `XXLARGE`, not merely "bigger than LARGE": Timeline asks for 1600 px, which
     * is inside `XLARGE`'s 2x cover band, so a version that named only `XLARGE` would also clear a
     * `> LARGE_PX` bar while still handing the hero a 1.56x upscale.
     */
    @Test fun joinerOpensAMomentAfterTheFeed_andTheHeroIsServedTheLargeRung() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (_, joiner, path) = hostWithPhotoAndPairedJoiner(system)
            val timeline = TimelineProjectionRepository(joiner.tenshuClient, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            var feedEdge: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                feedEdge = timeline
                    .photoBytes(path, GRID_TILE_PX, GRID_TILE_PX, heroFidelity = false)
                    ?.let(::decodedEdge)
                feedEdge != null
            }
            assertEquals(
                GetStoredThumbnail.LARGE_PX,
                feedEdge,
                "Precondition: the feed's row read leaves the joiner holding the LARGE class",
            )

            var heroEdge: Int? = null
            try {
                system.advanceUntil(timeout = 20.seconds) {
                    heroEdge = timeline
                        .photoBytes(path, TIMELINE_HERO_PX, TIMELINE_HERO_PX, heroFidelity = true)
                        ?.let(::decodedEdge)
                    (heroEdge ?: 0) > GetStoredThumbnail.LARGE_PX
                }
            } catch (e: TimeoutCancellationException) {
                fail(
                    "A $TIMELINE_HERO_PX px hero on a joiner that already holds LARGE must be " +
                        "served a named rung; it stayed at $heroEdge px",
                )
            }
            assertEquals(
                GetStoredThumbnail.XXLARGE_PX,
                heroEdge,
                "1600 px is above XLARGE_PX, so XXLARGE is what answers it — XLARGE would be a " +
                    "1.56x upscale that still clears a naive > LARGE_PX assertion",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠⚠ **A moment opened BEFORE its feed row — the ordering the test above cannot see, and the
     * one that caught a regression X-6's first cut shipped.**
     *
     * The hero arm returns early, and `cachePhotoThumbnails` — the only thing in Timeline that
     * pulls `LIST` + `LARGE` — is gated on `readPhotoPreview` being null. Once `XXLARGE` is local
     * that read is never null again, so skipping the pull once skips it **forever**: the photo ends
     * up holding only the 2048 px class.
     *
     * ⚠⚠ That is an amplification defect, not a missing-picture one, which is why nothing else
     * catches it. `StoredThumbnailLadder.covering` serves the smallest locally-present covering
     * class **verbatim, no downscale** — so a later 256 px feed row is answered with the 2048 px
     * blob: ~33x the bytes and a ~12.6 MB ARGB decode per frame, permanently.
     *
     * ⚠ Reachable without contrivance: `MomentDetailScreen` draws its hero before any `cached`
     * read for that photo, so a deep link, a cross-app open, or a row whose fetch was cancelled by
     * a fast scroll all arrive here first.
     */
    @Test fun aMomentOpenedBeforeItsFeedRow_stillLeavesTheSmallClassesPulled() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (_, joiner, path) = hostWithPhotoAndPairedJoiner(system)
            val timeline = TimelineProjectionRepository(joiner.tenshuClient, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            // The hero FIRST — nothing has read this photo at tile size yet.
            var heroEdge: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                heroEdge = timeline
                    .photoBytes(path, TIMELINE_HERO_PX, TIMELINE_HERO_PX, heroFidelity = true)
                    ?.let(::decodedEdge)
                (heroEdge ?: 0) > GetStoredThumbnail.LARGE_PX
            }
            assertEquals(GetStoredThumbnail.XXLARGE_PX, heroEdge, "precondition: the hero is the rung")

            // Now a feed row. It must be answered by LARGE, not by the hero blob served verbatim.
            var rowEdge: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                rowEdge = timeline
                    .photoBytes(path, GRID_TILE_PX, GRID_TILE_PX, heroFidelity = false)
                    ?.let(::decodedEdge)
                rowEdge != null
            }
            assertEquals(
                GetStoredThumbnail.LARGE_PX,
                rowEdge,
                "a $GRID_TILE_PX px row after a hero open must be served the LARGE class; " +
                    "$rowEdge px means the hero return skipped the small-class pull and the " +
                    "ladder is handing out the 2048 px blob verbatim",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ **`heroFidelity` is the only thing keeping the ~0.5 MB class out of the feed, and a feed
     * frame is not tile-sized.** `TimelineCards` and `PhotoCluster` draw `fillMaxWidth`, so
     * `previewRequestPx` buckets them to ≈1280 px on a phone — comfortably above
     * `asksForHeroByName`'s `LARGE_PX` floor. If the flag were dropped, or defaulted true, every
     * feed row would name `XXLARGE`: §14.2's trap at three times the cost, *"never a grid tile,
     * never a feed row"*.
     *
     * ⚠ The floor alone does not protect this. Only the flag does, and nothing else asserts it.
     */
    @Test fun aWideFeedFrameNeverNamesTheHeroRung() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (_, joiner, path) = hostWithPhotoAndPairedJoiner(system)
            val timeline = TimelineProjectionRepository(joiner.tenshuClient, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            var wideEdge: Int? = null
            system.advanceUntil(timeout = 20.seconds) {
                wideEdge = timeline
                    .photoBytes(path, WIDE_FEED_PX, WIDE_FEED_PX, heroFidelity = false)
                    ?.let(::decodedEdge)
                wideEdge != null
            }
            assertNotEquals(
                GetStoredThumbnail.XXLARGE_PX,
                wideEdge,
                "a $WIDE_FEED_PX px feed frame must never pull the ~0.5 MB XXLARGE class; only " +
                    "heroFidelity licenses that, and this request is well above the LARGE_PX floor",
            )
        } finally {
            system.shutdown()
        }
    }

    private companion object {
        /** The smallest request the Photos viewer makes — `heroPreviewRequestPx`'s floor, the hero rung. */
        const val HERO_REQUEST_PX = 1024

        /** A grid tile the `LARGE` rung fills - `tilePreviewRequestPx` below its threshold. */
        const val GRID_TILE_PX = 256

        /** Timeline's moment hero — `MomentDetailViewModel.COVER_EDGE`, above `XLARGE_PX`. */
        const val TIMELINE_HERO_PX = 1600

        /** A `fillMaxWidth` feed frame on a phone — what `previewRequestPx` actually buckets to. */
        const val WIDE_FEED_PX = 1280
    }
}
