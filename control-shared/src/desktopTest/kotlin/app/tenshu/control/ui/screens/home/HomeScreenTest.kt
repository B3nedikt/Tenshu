package app.tenshu.control.ui.screens.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tenshu.control.data.Event
import app.tenshu.control.data.EventRepository
import app.tenshu.control.ui.screens.apps.AppsViewModel
import app.tenshu.control.ui.screens.data.DataViewModel
import app.tenshu.control.ui.screens.services.ServicesViewModel
import app.tenshu.control.ui.screens.settings.SettingsViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeBlocking

class HomeScreenTest {

    private lateinit var mockEventRepository: EventRepository
    private val allEvents = MutableStateFlow<List<Event>>(emptyList())
    private val viewModels = OwnedViewModels()

    // ⚠ The store the composition resolves its `koinViewModel()`s into. Owned rather than
    // merely cleared: `clear()` cancels each `viewModelScope` without waiting for it.
    private val viewModelStore = viewModels.ownStore(ViewModelStore())
    private var currentPagingSource: PagingSource<Int, Event>? = null

    private fun now(): kotlin.time.Instant = Clock.System.now()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        allEvents.value = listOf(
            Event.FileEvent.Added(
                id = "1",
                timestamp = now(),
                fileName = "initial.jpg",
                preview = null
            ),
            Event.AppEvent.Registered(
                id = "2",
                timestamp = now(),
                appName = "Initial App"
            )
        )

        mockEventRepository = object : EventRepository {
            override fun getEvents(): Flow<PagingData<Event>> {
                return Pager(
                    config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                    pagingSourceFactory = {
                        object : PagingSource<Int, Event>() {
                            override fun getRefreshKey(state: PagingState<Int, Event>): Int? =
                                null

                            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Event> {
                                return LoadResult.Page(
                                    data = allEvents.value,
                                    prevKey = null,
                                    nextKey = null
                                )
                            }
                        }.also { currentPagingSource = it }
                    }
                ).flow
            }
        }

        startKoin {
            modules(module {
                single<EventRepository> { mockEventRepository }
                single<app.tenshu.control.data.AppRegistry> { app.tenshu.control.data.MockAppRegistry() }
                single<app.tenshu.control.data.RealmDataClient> { app.tenshu.control.data.MockRealmDataClient() }
                viewModel { HomeScreenViewModel(get()) }
                viewModel { AppsViewModel(get()) }
                viewModel { (appId: String) -> app.tenshu.control.ui.screens.apps.AppDetailViewModel(appId, get()) }
                viewModel { DataViewModel(get()) }
                single<app.tenshu.control.data.PublisherClient> {
                    app.tenshu.control.data.MockPublisherClient(
                        org.tenshu.core.common.delegate.InMemorySecureStorageDelegate(),
                    )
                }
                viewModel {
                    ServicesViewModel(
                        get(),
                        kotlinx.coroutines.flow.flowOf<app.tenshu.control.ui.screens.services.StorageStatusModel?>(null),
                    )
                }
                viewModel { SettingsViewModel() }
            })
        }
    }

    @After
    fun tearDown() {
        viewModels.closeBlocking()
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun homeScreenDisplaysEvents() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@HomeScreenTest.viewModelStore
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val viewModel = koinViewModel<HomeScreenViewModel>()
                    HomeScreen(viewModel = viewModel)
                }
            }
        }

        onNode(hasText("Recent Events"), useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText(
                "initial.jpg",
                substring = true,
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Initial App", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun addEvent_isReflectedInUI() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@HomeScreenTest.viewModelStore
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val viewModel = koinViewModel<HomeScreenViewModel>()
                    HomeScreen(viewModel = viewModel)
                }
            }
        }

        onNode(hasText("Recent Events"), useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText(
                "initial.jpg",
                substring = true,
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Add a new event
        allEvents.update {
            it + Event.FileEvent.Added(
                id = "3",
                timestamp = now(),
                fileName = "new_file.png",
                preview = null
            )
        }
        currentPagingSource?.invalidate()

        // Wait for it to show up
        waitUntil {
            onAllNodesWithText(
                "new_file.png",
                substring = true,
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun removeEvent_isReflectedInUI() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@HomeScreenTest.viewModelStore
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val viewModel = koinViewModel<HomeScreenViewModel>()
                    HomeScreen(viewModel = viewModel)
                }
            }
        }

        onNode(hasText("Recent Events"), useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText(
                "initial.jpg",
                substring = true,
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Remove the event
        allEvents.update { it.filterNot { event -> event.id == "1" } }
        currentPagingSource?.invalidate()

        // Wait for it to disappear
        waitUntil {
            onAllNodesWithText(
                "initial.jpg",
                substring = true,
                useUnmergedTree = true
            ).fetchSemanticsNodes().isEmpty()
        }
    }
}
