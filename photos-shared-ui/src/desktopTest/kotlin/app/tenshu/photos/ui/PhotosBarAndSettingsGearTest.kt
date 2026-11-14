package app.tenshu.photos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.ui.navigation.Destination
import app.tenshu.photos.ui.navigation.photosNavItems
import app.tenshu.photos.ui.navigation.selectTop
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CR-D8 (`photos-creation-features.md` §8): the bar is Photos · Collections — the Create tab is
 * deferred — and Settings is the ⚙ on the feed's top bar, pushed, so Back returns to the feed.
 *
 * ⚠ The harness is the real pairing `App.kt` uses — [AdaptiveNavScaffold] over [KmpNavHost], the real
 * [photosNavItems], [PhotosFeedTopAppBar], and [PushedTopAppBar], and `showNav` wired
 * verbatim — with stub bodies where a screen needs a repository.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class PhotosBarAndSettingsGearTest {

    @Test
    fun theBarIsPhotosCollections_andSettingsIsNoTab() {
        val items = photosNavItems(Destination.Feed) {}
        assertEquals(listOf("Photos", "Collections"), items.map { it.label })
        assertEquals(listOf<Any>(Destination.Feed, Destination.Collections), items.map { it.key })
        // ⚠⚠ Not a TopLevel: a tab selection would clear the stack, and Back would leave the app.
        assertFalse((Destination.Settings as Destination) is Destination.TopLevel)
    }

    @Test
    fun theGearPushesSettings_andBackReturnsToTheFeed() {
        runDesktopComposeUiTest(width = 420, height = 900) {
            val backStack = mutableStateListOf<Destination>(Destination.Feed)
            setContent { Shell(backStack) }
            waitForIdle()

            onNodeWithTag(FEED_SETTINGS_TAG).performClick()
            waitForIdle()
            assertEquals(listOf(Destination.Feed, Destination.Settings), backStack.toList(), "pushed")
            onNodeWithText(SETTINGS_BODY).assertIsDisplayed()
            // ⚠ The bar hides on a pushed screen, as on every other one.
            assertEquals(0, onAllNodesWithText("Collections").fetchSemanticsNodes().size)

            onNodeWithTag(PUSHED_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(listOf<Destination>(Destination.Feed), backStack.toList())
            onNodeWithText(FEED_BODY).assertIsDisplayed()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Shell(backStack: MutableList<Destination>) {
        TenshuTheme {
            val current = backStack.last()
            AdaptiveNavScaffold(
                navItems = photosNavItems(current) { backStack.selectTop(it) },
                showNav = current is Destination.TopLevel,
            ) {
                KmpNavHost(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = kmpEntryProvider {
                        entry<Destination.Feed> {
                            Scaffold(
                                topBar = {
                                    PhotosFeedTopAppBar(
                                        density = FeedDensity.entries.first(),
                                        onCycleDensity = {},
                                        onSearch = {},
                                        onSettings = { backStack.add(Destination.Settings) },
                                    )
                                },
                            ) { padding -> Text(FEED_BODY, Modifier.padding(padding)) }
                        }
                        entry<Destination.Settings> {
                            Scaffold(
                                topBar = {
                                    PushedTopAppBar("Settings") { backStack.removeLastOrNull() }
                                },
                            ) { padding -> Column(Modifier.padding(padding)) { Text(SETTINGS_BODY) } }
                        }
                        entry<Destination.Collections> { Text("collections body") }
                    },
                )
            }
        }
    }

    private companion object {
        const val FEED_BODY = "feed body"
        const val SETTINGS_BODY = "settings body"
    }
}
