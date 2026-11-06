package app.tenshu.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.navigation.Destination
import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.feed.PhotosFeedScreen
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeAndResetMain

/**
 * The feed keeps its place across a round trip through a pushed screen.
 *
 * ⚠⚠ **Pins that the nav host keeps the place**, a behaviour with THREE known ways to break
 * (`docs/tech-debt.md`, "scroll a list, open a detail, come back"): a scaffold rebuilding its
 * content subtree, a paged flow dropping its pages (no `cachedIn`), and no saveable-state registry
 * at all. The last is the nav host's job, and this is what says so if Photos ever switches
 * destinations with a plain `when`.
 *
 * ⚠ The harness is the real pairing — [AdaptiveNavScaffold] over [KmpNavHost] with `showNav` wired
 * exactly as `photos/ui/App.kt` wires it — because that pairing IS the mechanism. A
 * `PhotosFeedScreen` rendered on its own, as every other test in this package renders it, cannot
 * see any of it. ⚠⚠ `showNav = current is Destination.TopLevel` is copied verbatim: passing
 * `showNav` for a non-top-level destination can discard the whole nav host, so a
 * harness hard-coding `true` would be testing a shape the app does not have.
 */
@OptIn(ExperimentalTestApi::class)
class PhotosNavRoundTripTest {

    // ⚠⚠ `viewModelScope` needs a Main dispatcher and `runComposeUiTest` installs none — without
    // this every test here fails with "Dispatchers.Main was accessed when the platform dispatcher
    // was absent", which reads like a paging failure and is not one: it comes from the screen's
    // view model.
    //
    // ⚠ `Unconfined`, not a `StandardTestDispatcher`: the compose test drives its own clock, so a
    // scheduler nothing advances would leave the pager's first load pending forever and every
    // assertion would read an empty feed. It does mean this file cannot observe a load *in
    // flight* — a test that needs that wants a scheduler and no renderer.
    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDownMain() = viewModels.closeAndResetMain()

    /** Owns every view model these tests build; [closeAndResetMain] joins them before `resetMain()`. */
    private val viewModels = OwnedViewModels()

    /** ⚠ One photograph per day, so every day header is a DISTINCT string to assert on. */
    private fun photos(n: Int) = List(n) { i ->
        Photo("/p$i.jpg", (n - i) * DAY_MS, isVideo = false, contentId = "c$i")
    }

    @Test
    fun aScrolledFeedKeepsItsPlaceAfterOpeningAPhotographAndComingBack() {
        val all = photos(120)
        runDesktopComposeUiTest(width = 420, height = 900) {
            val backStack = mutableStateListOf<Destination>(Destination.Feed)
            setContent {
                TenshuTheme {
                    val current = backStack.last()
                    AdaptiveNavScaffold(
                        navItems = listOf(
                            AdaptiveNavItem(
                                key = Destination.Feed,
                                icon = Icons.Default.PhotoLibrary,
                                label = "Photos",
                                selected = true,
                                onClick = {},
                            ),
                        ),
                        showNav = current is Destination.TopLevel,
                    ) {
                        KmpNavHost(
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            entryProvider = kmpEntryProvider {
                                entry<Destination.Feed> {
                                    // ⚠⚠ The view model is remembered by the NAV ENTRY, which is
                                    // the whole point of this test: `KmpNavHost` keeps a
                                    // `ViewModelStore` per key, so surviving a round trip means
                                    // the pager and its `cachedIn` cache survive with it. A
                                    // `remember` outside the entry would pass while proving
                                    // nothing.
                                    val viewModel = remember {
                                        viewModels.own(
                                            PhotosFeedViewModel(
                                                repository = MockPhotosRepository(photos = all),
                                                sectionStartOf = { _, ms -> ms / DAY_MS * DAY_MS },
                                            ),
                                        )
                                    }
                                    PhotosFeedScreen(
                                        items = viewModel.items.collectAsLazyPagingItems(),
                                        loadPreview = { _, _ -> null },
                                        onPhotoClick = {},
                                        onAddToAlbum = {},
                                        onRetry = {},
                                        sectionLabel = { _, ms -> "day ${ms / DAY_MS}" },
                                    )
                                }
                                entry<Destination.Trash> {
                                    Box(Modifier.fillMaxSize()) { Text(DETAIL) }
                                }
                            },
                        )
                    }
                }
            }
            waitForIdle()

            // ⚠ The newest photograph is day 120 and sits at the very top. Its header being on
            // screen is what "the feed is at the top" MEANS, which is why the whole test is phrased
            // against it rather than against an index — no arithmetic about rows or spans, and it
            // fails for every version of this bug the repo has had.
            onNodeWithText(TOP_DAY).assertIsDisplayed()

            onNode(hasScrollToIndexAction()).performScrollToIndex(80)
            waitForIdle()
            onNodeWithText(TOP_DAY).assertDoesNotExist()

            backStack.add(Destination.Trash)
            waitForIdle()
            onNodeWithText(DETAIL).assertIsDisplayed()

            backStack.removeLastOrNull()
            waitForIdle()

            assertEquals(1, backStack.size, "the pop must land back on the feed")
            // ⚠⚠ THE assertion. If the feed came back at row 0 the top day is on screen again, and
            // that is precisely the bug — for all three of its causes.
            onNodeWithText(TOP_DAY).assertDoesNotExist()
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val TOP_DAY = "day 120"
        const val DETAIL = "A photograph"
    }
}
