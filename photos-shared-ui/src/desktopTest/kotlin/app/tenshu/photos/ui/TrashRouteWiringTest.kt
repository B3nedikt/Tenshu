package app.tenshu.photos.ui

import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.di.mockPhotosRepositoryModule
import app.tenshu.photos.di.viewModelsModule
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Trash route's view model, as Android's DI builds it (`photos-ios.md` §8.17).
 *
 * ⚠⚠ Pins that the module passes the route's `onChanged` hook: built as
 * `TrashViewModel(get())`, the hook stays the default no-op and Collections comes back stale after
 * a restore. Only the module itself can show that — every view-model test constructs the view
 * model by hand, hook included.
 */
class TrashRouteWiringTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theRoutesHookReachesTheViewModelTheModuleBuilds() = runTest(dispatcher) {
        val koin = koinApplication { modules(mockPhotosRepositoryModule(), viewModelsModule()) }.koin
        val repository = koin.get<PhotosRepository>()
        repository.trash(Photo("/p1.jpg", 1L, isVideo = false, contentId = "c1"))

        var changed = 0
        val vm = koin.get<TrashViewModel> { parametersOf({ changed++ }) }
        testScheduler.advanceUntilIdle()
        vm.restore("c1")
        testScheduler.advanceUntilIdle()

        assertEquals(1, changed, "the restore told the route, which re-reads Collections")
        koin.close()
    }
}
