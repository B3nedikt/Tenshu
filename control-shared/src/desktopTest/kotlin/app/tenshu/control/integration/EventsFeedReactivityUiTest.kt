package app.tenshu.control.integration

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.control.data.EventRepository
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import app.tenshu.control.ui.screens.home.HomeScreenViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.onFailure
import org.tenshu.file.client.fileService
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeBlocking

/**
 * ⚠⚠ **Control's guard on `cachedIn`** — `core-paging-scroll-restoration.md` SR-4.
 *
 * `HomeScreenViewModel.eventFlow` is `cachedIn(viewModelScope)`, on the one screen whose entire job
 * is an ambient event feed that updates while you watch it. This pins that feed's reactivity:
 * it drives the real proxy → the real `HomeScreenViewModel` → the real `LazyPagingItems` differ,
 * and asserts an event raised while the feed is on screen appears **without leaving the tab**.
 * [EventsFeedIntegrationTest] exercises `getEvents()` at the *repository* level via `asSnapshot()`,
 * below the view model, so it never sees `cachedIn` at all; `HomeScreenTest` swaps in a fake
 * repository.
 *
 * ⚠ If it goes red, the ban is back for Control: read `core-paging-scroll-restoration.md` §7, which
 * holds the design that routes around it, rather than weakening the assertion. Drive's twin is
 * `FilesPagingReactivityUiTest`, Fit's is `HistoryFeedReactivityTest`.
 *
 * ⚠⚠ **Non-vacuous** — a reactivity test that cannot fail is worse than none, because it reads as
 * coverage. Insert a `.take(1)` before `collectAsLazyPagingItems()`, so
 * only the first generation is ever delivered, and this test **fails** at the `/beta` wait. That is
 * the shape of the swallow it exists to catch.
 *
 * NOTE: one test per class — a second `runComposeUiTest` in the same JVM races the live observer's
 * teardown (the note Drive's twin carries for the same reason).
 */
class EventsFeedReactivityUiTest {

    private val viewModels = OwnedViewModels()

    // ⚠ Joined BEFORE the proxy's Koin graph is torn down: this view model collects a live
    // repository flow, and the shutdown's last emission is what would dispatch onto a `Main` the
    // test has already left.
    @After
    fun tearDown() {
        viewModels.closeBlocking()
        stopAppIntegrationKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEventRaisedWhileTheFeedIsOpenAppearsWithoutLeavingTheTab() = runComposeUiTest {
        startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        val client = GlobalContext.get().get<TenshuClient>()
        val viewModel = viewModels.own(HomeScreenViewModel(GlobalContext.get().get<EventRepository>()))

        // Seed one event so the initial generation is non-empty.
        runBlocking { client.fileService.defaultFileSystem.createFolder("/alpha").onFailure { error(it) } }

        setContent {
            TenshuTheme {
                val events = viewModel.eventFlow.collectAsLazyPagingItems()
                Column {
                    for (i in 0 until events.itemCount) {
                        // The folder name is what makes each row identifiable; the event's own
                        // rendering lives in the real screen and is not what this test is about.
                        events[i]?.let { Text(it.toString()) }
                    }
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("alpha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Raise more while the SAME collection stays open. Successive, because the failure this
        // guards against lets the first through and drops the rest.
        runBlocking { client.fileService.defaultFileSystem.createFolder("/beta").onFailure { error(it) } }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("beta", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { client.fileService.defaultFileSystem.createFolder("/gamma").onFailure { error(it) } }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("gamma", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
