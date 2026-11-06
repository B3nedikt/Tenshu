package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The viewer bar's two live fields: the star, and the place half of the subtitle.
 *
 * ⚠⚠ The load-bearing tests here are the negative ones. A star that silently does nothing and a
 * subtitle that names the wrong city are both **quiet** failures — nothing throws, nothing logs, and
 * the screen looks finished.
 */
class ViewerFavouriteAndPlaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ⚠ E7 is degrees × 10^7. 48.8584, 2.2945 is the Eiffel Tower.
    private val eiffelLat = 488_584_000L
    private val eiffelLng = 22_945_000L

    private val eiffel = PhotoPlace("p-eiffel", "Eiffel Tower", "Paris", eiffelLat, eiffelLng)

    /**
     * ⚠⚠ ~150 m north — **inside** the 250 m radius, and listed *before* the Eiffel Tower.
     *
     * A rival 3 km away would be removed by the radius filter before the nearest-of rule ever ran:
     * the test would pass with `minByOrNull` replaced by `firstOrNull`, i.e. it would check
     * "in range" while claiming to check "nearest". Two cafés
     * fifty metres apart is the case `groupPhotosByPlace` names, and it only bites when both
     * survive the filter.
     */
    private val alsoClose = PhotoPlace("p-close", "Champ de Mars", "Paris", eiffelLat + 13_500L, eiffelLng)

    private val far = PhotoPlace("p-far", "Gare du Nord", "Paris", 488_800_000L, 23_600_000L)

    private fun photo(
        path: String = "/a.jpg",
        contentId: String = "c-a",
        latE7: Long? = null,
        lngE7: Long? = null,
    ) = Photo(path, 1_000L, isVideo = false, contentId = contentId, latE7 = latE7, lngE7 = lngE7)

    @Test
    fun aPhotoWithNoContentIdCannotBeFavourited() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Every file uploaded before content addressing is in this state and the flag ops refuse
        // it. The star must render **disabled**, not hollow-and-failing.
        val vm = vms.own(PhotoViewerViewModel(MockPhotosRepository(), photo(contentId = "")))
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.canFavourite)
        assertNull(vm.state.value.favourite, "unfavouritable is not the same claim as not-favourited")

        vm.toggleFavourite()
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.favourite, "tapping a disabled star must not fake a write")
    }

    @Test
    fun theStarRevertsWhenTheWriteIsRefused() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The whole reason the optimistic update is written out rather than assumed. A star left
        // filled over a flag that was never stored makes the viewer and the Favourites album
        // disagree, and the user believes the version that is wrong.
        val repo = object : PhotosRepository by MockPhotosRepository() {
            override suspend fun setFavourite(contentId: String, favourite: Boolean) = false
        }
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleFavourite()
        assertEquals(true, vm.state.value.favourite, "optimistic while the write is in flight")
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.state.value.favourite, "a refused write puts the star back")
    }

    @Test
    fun theStarFollowsTheSwipe() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(PhotoViewerViewModel(repo, photo(path = "/a.jpg", contentId = "c-a")))
        testScheduler.advanceUntilIdle()
        vm.toggleFavourite()
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.favourite)

        vm.onPhotoShown(photo(path = "/b.jpg", contentId = "c-b"))
        // ⚠ Before the read lands: the previous photograph's star must not survive the swipe, or
        // the bar asserts a favourite the new row never claimed.
        assertNull(vm.state.value.favourite)
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.state.value.favourite)
    }

    @Test
    fun thePlaceIsTheNearestNamedOneInRange() = vmTest(dispatcher) { vms ->
        val repo = object : PhotosRepository by MockPhotosRepository() {
            override suspend fun places() = listOf(alsoClose, far, eiffel)
        }
        val vm = vms.own(PhotoViewerViewModel(repo, photo(latE7 = eiffelLat, lngE7 = eiffelLng)))
        testScheduler.advanceUntilIdle()

        assertEquals(
            "Eiffel Tower",
            vm.state.value.place?.label,
            "nearest, not first — `alsoClose` is in range and listed ahead of it",
        )
    }

    @Test
    fun aPhotoWithNoCoordinateNamesNoPlace() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The common case, and the one a subtitle is most tempted to fill in. Most photographs
        // carry no GPS at all — `Photo.latE7`'s KDoc — and there is no honest fallback.
        val repo = object : PhotosRepository by MockPhotosRepository() {
            override suspend fun places() = listOf(eiffel)
        }
        val vm = vms.own(PhotoViewerViewModel(repo, photo(latE7 = null, lngE7 = null)))
        testScheduler.advanceUntilIdle()

        assertNull(vm.state.value.place)
    }

    @Test
    fun aCoordinateFarFromEveryPlaceNamesNoPlace() = vmTest(dispatcher) { vms ->
        // Somewhere in the Atlantic. ⚠ Nothing is "nearest" at any distance — the radius is a
        // refusal, not a preference.
        val repo = object : PhotosRepository by MockPhotosRepository() {
            override suspend fun places() = listOf(eiffel)
        }
        val vm = vms.own(PhotoViewerViewModel(repo, photo(latE7 = 100_000_000L, lngE7 = -200_000_000L)))
        testScheduler.advanceUntilIdle()

        assertNull(vm.state.value.place)
    }

    @Test
    fun anUnnamedPlaceIsNeverTheAnswer() = vmTest(dispatcher) { vms ->
        // ⚠⚠ `Place.label` is empty until something geocodes it (PH-G7 is unshipped), so an
        // unnamed place is the *usual* state of a place, not an edge case.
        val repo = object : PhotosRepository by MockPhotosRepository() {
            override suspend fun places() =
                listOf(eiffel.copy(label = "", city = ""))
        }
        val vm = vms.own(PhotoViewerViewModel(repo, photo(latE7 = eiffelLat, lngE7 = eiffelLng)))
        testScheduler.advanceUntilIdle()

        assertNull(vm.state.value.place)
    }

    @Test
    fun openingTheInfoSheetForcesTheChromeBack() = vmTest(dispatcher) { vms ->
        // ⚠ Otherwise a sheet dragged up over hidden chrome, then dismissed, leaves the user on a
        // bare photograph with no way out (§3.1b).
        val vm = vms.own(PhotoViewerViewModel(MockPhotosRepository(), photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleChrome()
        assertFalse(vm.state.value.chromeVisible)

        vm.toggleInfo()
        assertTrue(vm.state.value.chromeVisible)
    }

    @Test
    fun aTapBeforeTheFlagIsKnownIsNotTaken_soTheStarNeverDisagreesWithTheStore() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The read of the flag is in flight; a tap that treats the unknown as "not favourited"
        // writes `true` — and the read, landing after, sets the star back to `false` over a flag
        // the store now holds as `true`. The star and the Favourites album then disagree, which is
        // exactly what the revert-on-refusal exists to prevent.
        val repo = MockPhotosRepository()
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        assertNull(vm.state.value.favourite, "precondition: the read has not landed")

        vm.toggleFavourite()
        testScheduler.advanceUntilIdle()

        assertEquals(repo.isFavourite("c-a"), vm.state.value.favourite, "the star says what the store holds")
        assertFalse(repo.isFavourite("c-a"), "and the early tap was not taken")
    }

    /** A store whose flag writes wait for [gate] — a proxy slow enough for a second tap. */
    private class SlowFavourites(
        private val delegate: MockPhotosRepository = MockPhotosRepository(),
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        var gate = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
        val writes = mutableListOf<Boolean>()
        override suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean {
            gate.await()
            writes += favourite
            return delegate.setFavourite(contentId, favourite)
        }
    }

    @Test
    fun aSecondTapWhileTheFirstIsWritingIsNotTaken_soTheStoreEndsWhereTheStarSays() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Two writes in flight end in whichever finishes LAST. The second tap is
        // not taken while the first writes, and the shells disable the star on `writing`.
        val repo = SlowFavourites()
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()
        repo.gate = kotlinx.coroutines.CompletableDeferred()

        vm.toggleFavourite()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.writing, "the first write is in flight")
        vm.toggleFavourite()
        repo.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(true), repo.writes, "one write")
        assertEquals(repo.isFavourite("c-a"), vm.state.value.favourite, "the star says what the store holds")
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun aWriteThatSucceededStands_whenASwipeBackReadTheOldFlag() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A → B → A while A's write runs clears the star and reads the flag again; that read can
        // return the OLD value, so a success must still set the star — or a hollow star sits over a
        // stored favourite.
        val repo = SlowFavourites()
        val vm = vms.own(PhotoViewerViewModel(repo, photo(path = "/a.jpg", contentId = "c-a")))
        testScheduler.advanceUntilIdle()
        repo.gate = kotlinx.coroutines.CompletableDeferred()

        vm.toggleFavourite()
        vm.onPhotoShown(photo(path = "/b.jpg", contentId = "c-b"))
        vm.onPhotoShown(photo(path = "/a.jpg", contentId = "c-a"))
        testScheduler.advanceUntilIdle() // the swipe-back read lands while the write is still held
        repo.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(repo.isFavourite("c-a"))
        assertEquals(true, vm.state.value.favourite, "the star says what the store holds")
    }
}

