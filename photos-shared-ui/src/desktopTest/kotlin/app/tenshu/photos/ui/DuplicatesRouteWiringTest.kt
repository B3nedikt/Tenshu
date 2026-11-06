package app.tenshu.photos.ui

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.di.viewModelsModule
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Duplicates route's view model, as Android's DI builds it (`photos-ios.md` §8.18).
 *
 * ⚠⚠ `TrashRouteWiringTest`'s rule, for a route with the same hook: a module that builds
 * `DuplicatesViewModel(get())` drops `onChanged`, so a keep-only trashes photographs and the
 * Collections tab comes back with the counts it had before. Only the module itself can show that.
 */
class DuplicatesRouteWiringTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theRoutesHookReachesTheViewModelTheModuleBuilds() = runTest(dispatcher) {
        val library = MockPhotosRepository(
            listOf(
                Photo("/p1.jpg", 1L, isVideo = false, contentId = "c1", contentHash = "h1"),
                Photo("/p2.jpg", 2L, isVideo = false, contentId = "c2", contentHash = "h1"),
            ),
        )
        val koin = koinApplication {
            modules(module { single<PhotosRepository> { library } }, viewModelsModule())
        }.koin

        var changed = 0
        val vm = koin.get<DuplicatesViewModel> { parametersOf({ changed++ }) }
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        val group = state.groups.single()
        vm.keepOnly(state.confirmation(group, group.photos.first())!!)
        testScheduler.advanceUntilIdle()

        assertEquals(1, changed, "the keep-only told the route, which re-reads Collections")
        koin.close()
    }
}
