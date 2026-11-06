package app.tenshu.fit.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.fit.ui.screens.category.CategoryMetric
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import kotlin.test.assertTrue
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for Home: the seeded aggregates + latest workout flow through
 * [HomeViewModel] into the Active-Minutes surface and the latest-workout card, so the tab
 * renders content (not the loading/empty state). Mirrors Drive/Control's HomeScreenTest.
 */
@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {

    @Test
    fun seededHomeRendersActivityAndLatestWorkout() = composeVmTest { vms ->
        val vm = vms.own(HomeViewModel(MockFitRepository()))
        setContent {
            TenshuTheme {
                HomeScreen(
                    viewModel = vm,
                    onOpenWorkout = {},
                    onOpenHistory = {},
                    onStartRecording = {},
                )
            }
        }

        // The Active-Minutes ring label proves HomeContent (not the loading/empty branch) rendered.
        waitUntil {
            onAllNodesWithText("Active minutes", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The newest seeded workout (2026-05-24) surfaces as the latest-workout card — below the
        // four week cards, so scroll to it first.
        onNodeWithText("Morning run", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * Home scrolls as one page (fit-app.md §18.16): the bar and the date block both leave with the
     * content. Red on either regression — a pinned bar keeps "Home" in place, and a date block
     * hoisted back above the scroll area keeps "TODAY" in place.
     */
    @Test
    fun homeScrollsAsOnePageBarAndDateIncluded() = composeVmTest { vms ->
        val vm = vms.own(HomeViewModel(MockFitRepository()))
        setContent {
            TenshuTheme {
                // A phone-sized viewport, so the content is taller than the screen and can scroll.
                Box(Modifier.requiredSize(width = 400.dp, height = 500.dp)) {
                    HomeScreen(
                        viewModel = vm,
                        onOpenWorkout = {},
                        onOpenHistory = {},
                        onStartRecording = {},
                    )
                }
            }
        }
        waitUntil {
            onAllNodesWithText("TODAY", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val titleBefore = onNodeWithText("Home", useUnmergedTree = true).getBoundsInRoot().top
        val dateBefore = onNodeWithText("TODAY", useUnmergedTree = true).getBoundsInRoot().top

        onRoot().performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
        waitForIdle()

        val titleAfter = onNodeWithText("Home", useUnmergedTree = true).getBoundsInRoot().top
        val dateAfter = onNodeWithText("TODAY", useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(titleAfter < titleBefore, "the bar should scroll away: $titleBefore -> $titleAfter")
        assertTrue(dateAfter < dateBefore, "the date should scroll with the page: $dateBefore -> $dateAfter")
    }

    /**
     * fit-app.md §18.18: four week cards in Google Fit's order, each opening its own series, and
     * the three values under the ring opening theirs.
     */
    @Test
    fun weekCardsAndStatsOpenTheirMetric() = composeVmTest { vms ->
        val vm = vms.own(HomeViewModel(MockFitRepository()))
        val opened = mutableListOf<CategoryMetric>()
        setContent {
            TenshuTheme {
                HomeScreen(
                    viewModel = vm,
                    onOpenWorkout = {},
                    onOpenHistory = {},
                    onStartRecording = {},
                    onOpenMetric = { opened += it },
                )
            }
        }
        waitUntil {
            onAllNodesWithText("Active minutes", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Card titles, top to bottom. "Energy"/"Steps"/"Distance" are also the stat labels above,
        // so each card title is the LAST node with that text. ⚠ positionInRoot, not boundsInRoot:
        // the bounds are clipped to the viewport and read 0 for cards below it.
        val titles = listOf("Energy", "Steps", "Active minutes", "Distance")
        val tops = titles.map { t ->
            onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().last().positionInRoot.y
        }
        assertEquals(tops.sorted(), tops, "cards out of order: ${titles.zip(tops)}")

        for ((title, metric) in titles.zip(listOf(
            CategoryMetric.ActiveEnergy, CategoryMetric.Steps, CategoryMetric.ActiveMinutes, CategoryMetric.Distance,
        ))) {
            val node = onAllNodesWithText(title, useUnmergedTree = true).onLast()
            node.performScrollTo()
            node.performClick()
            waitForIdle()
            assertEquals(metric, opened.last(), "tapping the $title card")
        }

        // The values under the ring (mock: 6,120 steps · 4.5 km · 464 kcal).
        opened.clear()
        for (value in listOf("6,120", "4.5", "464")) {
            onNodeWithText(value, useUnmergedTree = true).performScrollTo().performClick()
            waitForIdle()
        }
        assertEquals(listOf(CategoryMetric.Steps, CategoryMetric.Distance, CategoryMetric.ActiveEnergy), opened)
    }
}
