package app.tenshu.timeline.ui.screens

import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.ui.screens.moment.MomentDetailUiState
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The moment-detail photo hero (`timeline-photos.md` T-P1…T-P3).
 *
 * ⚠ Since T-P3 the view model no longer asks *which* photos a moment has — the projection assigns
 * them and the moment arrives carrying them, which is what makes the hero and the feed row show the
 * same photograph. What is pinned here is everything around that: the fetch is best-effort and must
 * never unshow a moment already on screen, it must not fire where a different hero wins, and it
 * must not leave a photo-shaped hole where there is no photo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentPhotoHeroTest {

    private val mock = MockTimelineRepository()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val photo = Photo(dominantColor = "#123456", uri = "/Backup/Phone/Camera/IMG_1.jpg")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun firstMoment(): Moment = mock.homeDays().first().first().moments.first()

    /** A repository whose moments carry [photo], and which can answer for its bytes. */
    private inner class WithPhoto(
        private val photoBytes: ByteArray? = bytes,
        private val moment: suspend () -> Moment = { firstMoment().copy(photos = listOf(photo)) },
    ) : TimelineRepository by mock {
        var byteReads = 0
        override suspend fun moment(id: String, fresh: Boolean): Moment = moment.invoke()
        override suspend fun photoBytes(
            uri: String,
            width: Int,
            height: Int,
            heroFidelity: Boolean,
        ): ByteArray? {
            byteReads++
            return photoBytes
        }
    }

    @Test
    fun aPhotoTheProjectionAssignedBecomesTheHero() = vmTest { vms ->
        val repository = WithPhoto()
        val vm = vms.own(MomentDetailViewModel(repository, "any"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertContentEquals(bytes, state.coverPhotoBytes)
        assertEquals(photo.uri, state.coverPhoto?.uri)
        assertEquals(1, repository.byteReads, "the hero reads bytes once, not per recomposition")
    }

    @Test
    fun theHeroAsksForHeroFidelity_neverSettlingForTheCachedCeiling() = vmTest { vms ->
        // ⚠⚠ `heroFidelity` *defaults* to false, so every other test in this file — none of which
        // inspects it — passes whether or not the hero asks for the sharp rung. Without this test
        // the flag could be dropped at the call site and nothing would notice.
        //
        // The distinction is not cosmetic: on any device that did not upload the photo, a read
        // without it tops out at the 256 px `LARGE` class, so a hero drawn from it is a 3-5×
        // upscale (see `TimelineRepository.photoBytes`).
        val repository = object : TimelineRepository by mock {
            var reads = 0
            var sawHeroFidelity: Boolean? = null
            var requested: Pair<Int, Int>? = null
            override suspend fun moment(id: String, fresh: Boolean) =
                firstMoment().copy(photos = listOf(photo))

            override suspend fun photoBytes(
                uri: String,
                width: Int,
                height: Int,
                heroFidelity: Boolean,
            ): ByteArray? {
                reads++
                sawHeroFidelity = heroFidelity
                requested = width to height
                return bytes
            }
        }

        val vm = vms.own(MomentDetailViewModel(repository, "any"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)

        assertContentEquals(bytes, state.coverPhotoBytes)
        assertEquals(1, repository.reads, "the hero reads once, not per recomposition")
        assertEquals(
            true,
            repository.sawHeroFidelity,
            "the hero must ask for the XLARGE rung — a read without it tops out at 256 px",
        )

        // ⚠ Square, not the hero band's own shape: the band draws with `ContentScale.Crop` while
        // `GetPreview` *fits*, so asking for a 220-tall band's aspect returns a photo only as wide
        // as the band is tall — which the crop then blows straight back up.
        val (width, height) = assertNotNull(repository.requested)
        assertEquals(width, height, "a cropping hero must request a square, not its own aspect")
    }

    @Test
    fun aMomentWithNoPhotosKeepsTheHeroItAlreadyHad() = vmTest { vms ->
        // The mock's moments carry none. Nothing must appear — a hero band drawn over no photo is
        // worse than no band, and this is the state almost every moment is in.
        val vm = vms.own(MomentDetailViewModel(mock, firstMoment().id))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertNull(state.coverPhoto)
        assertNull(state.coverPhotoBytes)
    }

    @Test
    fun aPhotoWhoseBytesThisDeviceDoesNotHaveShowsNoHeroAtAll() = vmTest { vms ->
        // `File.Thumbnail` is OnDemand, so a device that has not fetched the blob legitimately
        // answers null. Surfacing the photo *without* its bytes would render an empty tinted band
        // that looks like a broken image rather than an absent one.
        val vm = vms.own(MomentDetailViewModel(WithPhoto(photoBytes = null), "any"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertNull(state.coverPhoto, "photo and bytes are surfaced together or not at all")
        assertNull(state.coverPhotoBytes)
    }

    @Test
    fun aFailedByteReadLeavesTheMomentOnScreen() = vmTest { vms ->
        // Same rule as the route hero: the moment has already rendered. An IPC throw here must not
        // escape the launch (fatal on Android) and must not replace a good screen with an error.
        val exploding = object : TimelineRepository by mock {
            override suspend fun moment(id: String, fresh: Boolean) =
                firstMoment().copy(photos = listOf(photo))
            override suspend fun photoBytes(
                uri: String,
                width: Int,
                height: Int,
                heroFidelity: Boolean,
            ): ByteArray? = throw IllegalStateException("Proxy died mid-call")
        }
        val vm = vms.own(MomentDetailViewModel(exploding, "any"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertNull(state.coverPhotoBytes)
    }

    @Test
    fun aMovementWithARouteNeverFetchesAPhoto() = vmTest { vms ->
        // The route map is that moment's hero, so any photo bytes read would be thrown away — and
        // on a device that has not cached the blob, that read costs a content-plane fetch.
        val repository = object : TimelineRepository by mock {
            var byteReads = 0
            override suspend fun moment(id: String, fresh: Boolean) = firstMoment().copy(
                id = "movement-with-route",
                kind = MomentKind.Movement,
                traceId = "trace-1",
                photos = listOf(photo),
            )
            override suspend fun movementRoute(traceIds: List<String>): List<GeoPoint> =
                listOf(GeoPoint(43.77, 11.25), GeoPoint(43.78, 11.26))
            override suspend fun photoBytes(
                uri: String,
                width: Int,
                height: Int,
                heroFidelity: Boolean,
            ): ByteArray? {
                byteReads++
                return bytes
            }
        }
        val vm = vms.own(MomentDetailViewModel(repository, "movement-with-route"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertTrue(state.route.size >= 2, "the route hero is what rendered")
        assertEquals(0, repository.byteReads, "a drawn route must suppress the photo fetch entirely")
    }

    @Test
    fun aMovementWithNoDrawableRouteStillGetsItsPhotoHero() = vmTest { vms ->
        // A trace can be missing or too short to draw. Then the movement falls back to the same
        // photo hero as every other kind — it must not end up with no hero and no fetch.
        val repository = WithPhoto(
            moment = {
                firstMoment().copy(
                    id = "movement-without-route",
                    kind = MomentKind.Movement,
                    traceId = "trace-1",
                    photos = listOf(photo),
                )
            },
        )
        val vm = vms.own(MomentDetailViewModel(repository, "movement-without-route"))
        val state = assertIs<MomentDetailUiState.Loaded>(vm.uiState.value)
        assertEquals(1, repository.byteReads)
        assertContentEquals(bytes, state.coverPhotoBytes)
    }
}
