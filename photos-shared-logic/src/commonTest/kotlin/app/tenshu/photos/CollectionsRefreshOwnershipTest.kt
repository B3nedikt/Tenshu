package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.SectionLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.viewmodel.test.vmTest

/**
 * Refresh ownership under an IMMEDIATE dispatcher — Kotlin/Native's `viewModelScope`
 * is `Main.immediate`, and a refresh whose reads answer without suspending finishes before `launch`
 * returns its job.
 */
class CollectionsRefreshOwnershipTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Counting(private val inner: PhotosRepository) : PhotosRepository by inner {
        var albumReads = 0
        var failFavourites = false
        var failPlaces = false
        var failLibrary = false
        override suspend fun allPhotosRead(): List<Photo>? = if (failLibrary) null else inner.allPhotosRead()
        var failFaces = false
        override suspend fun albums(): AlbumsResult = inner.albums().also { albumReads++ }
        override suspend fun placesRead(): List<app.tenshu.photos.model.PhotoPlace>? = if (failPlaces) null else inner.placesRead()
        override suspend fun faceGroups(limit: Int): Pair<List<app.tenshu.photos.model.FaceGroup>, app.tenshu.photos.model.PeopleStatus> =
            if (failFaces) emptyList<app.tenshu.photos.model.FaceGroup>() to app.tenshu.photos.model.PeopleStatus.Failed else inner.faceGroups(limit)
        override suspend fun favourites(): PhotoPageResult =
            if (failFavourites) PhotoPageResult.Failed("boom") else inner.favourites()
    }

    private val photo = Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a")

    /** ⚠⚠ The latch must be released even when the refresh completes inside `launch` itself. */
    @Test
    fun aRefreshThatCompletesImmediatelyDoesNotLatchTheNextOneOut() = vmTest(dispatcher) { vms ->
        val repository = Counting(MockPhotosRepository(photos = listOf(photo)))
        val vm = vms.own(CollectionsViewModel(repository))
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads)

        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(2, repository.albumReads, "the second refresh ran")
    }

    /** A failed favourites read is not passed off as the last count. */
    @Test
    fun aFailedFavouritesReadIsReportedNotHidden() = vmTest(dispatcher) { vms ->
        val repository = Counting(MockPhotosRepository(photos = listOf(photo)))
        repository.failFavourites = true
        val vm = vms.own(CollectionsViewModel(repository))
        testScheduler.advanceUntilIdle()

        assertEquals(SectionLoad.FAILED, vm.state.value.favouritesLoad)
    }

    /** A failed list read is a failed section, not an empty one. */
    @Test
    fun aFailedPlacesReadFailsPlaces_notEmptiesIt() = vmTest(dispatcher) { vms ->
        val repository = Counting(MockPhotosRepository(photos = listOf(photo)))
        repository.failPlaces = true
        val vm = vms.own(CollectionsViewModel(repository))
        testScheduler.advanceUntilIdle()
        assertEquals(SectionLoad.FAILED, vm.state.value.placesLoad)
    }

    /** A generic People failure gets the section's Try again — it is a failed read. */
    @Test
    fun aGenericPeopleFailureFailsTheSection() = vmTest(dispatcher) { vms ->
        val repository = Counting(MockPhotosRepository(photos = listOf(photo)))
        repository.failFaces = true
        val vm = vms.own(CollectionsViewModel(repository))
        testScheduler.advanceUntilIdle()
        assertEquals(SectionLoad.FAILED, vm.state.value.peopleLoad)
    }

    /** A library read that fails promptly fails the walk — never a partial library. */
    @Test
    fun aPromptlyFailedLibraryReadFailsTheWalk() = vmTest(dispatcher) { vms ->
        val repository = Counting(MockPhotosRepository(photos = listOf(photo)))
        repository.failLibrary = true
        val vm = vms.own(CollectionsViewModel(repository))
        testScheduler.advanceUntilIdle()
        assertEquals(SectionLoad.FAILED, vm.state.value.libraryLoad)
        assertEquals(emptyList(), vm.state.value.years)
    }
}
