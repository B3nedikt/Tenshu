package app.tenshu.ui.feedback

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.tenshu.sync.common.SyncActivity
import kotlin.test.Test
import kotlin.test.assertEquals

/** `suite-sync-status.md` SS-2: the Compose "Syncing…" capsule. */
@OptIn(ExperimentalTestApi::class)
class SyncActivityCapsuleTest {

    @Test
    fun aPull_showsSyncing_andIdleOrHiddenShowsNothing() = runComposeUiTest {
        var activity: SyncActivity by mutableStateOf(SyncActivity.CatchingUp)
        var hidden by mutableStateOf(false)
        setContent {
            TenshuTheme { Box(Modifier.fillMaxSize()) { SyncActivityCapsule(activity, hidden) } }
        }
        onNodeWithTag(SYNC_ACTIVITY_CAPSULE_TAG).assertIsDisplayed()

        // ⚠ A problem strip hides it: one thing at a time.
        hidden = true
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(SYNC_ACTIVITY_CAPSULE_TAG).fetchSemanticsNodes().size)

        hidden = false
        activity = SyncActivity.Idle
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(SYNC_ACTIVITY_CAPSULE_TAG).fetchSemanticsNodes().size)
    }

    /** ⚠⚠ It covers the last row, so it must never swallow a tap meant for that row. */
    @Test
    fun aTapOnTheCapsule_reachesTheRowUnderneath() = runComposeUiTest {
        var rowTaps = 0
        setContent {
            TenshuTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("row").clickable { rowTaps++ })
                    SyncActivityCapsule(SyncActivity.CatchingUp, hidden = false)
                }
            }
        }
        onNodeWithTag(SYNC_ACTIVITY_CAPSULE_TAG).performClick()
        assertEquals(1, rowTaps)
    }

    /**
     * The iOS timing, shared: a caught-up device pulling one quick page never shows it, and one that
     * is really catching up does.
     */
    @Test
    fun aBriefBlip_neverShows_andALongPullDoes() = runComposeUiTest {
        mainClock.autoAdvance = false
        val catchingUp = MutableStateFlow(false)
        setContent {
            TenshuTheme {
                Box(Modifier.fillMaxSize()) {
                    SyncActivityCapsule(rememberSyncActivity(catchingUp), hidden = false)
                }
            }
        }
        mainClock.advanceTimeByFrame()
        catchingUp.value = true
        mainClock.advanceTimeBy(300)
        catchingUp.value = false
        mainClock.advanceTimeBy(2_000)
        assertEquals(0, onAllNodesWithTag(SYNC_ACTIVITY_CAPSULE_TAG).fetchSemanticsNodes().size, "a blip")

        catchingUp.value = true
        mainClock.advanceTimeBy(1_500)
        onNodeWithTag(SYNC_ACTIVITY_CAPSULE_TAG).assertIsDisplayed()
    }

    /** ⚠ While it fades out it still says what it said, never a bare glyph or an empty label. */
    @Test
    fun goingIdle_keepsTheWordsWhileItFades() = runComposeUiTest {
        mainClock.autoAdvance = false
        var activity: SyncActivity by mutableStateOf(SyncActivity.CatchingUp)
        setContent {
            TenshuTheme { Box(Modifier.fillMaxSize()) { SyncActivityCapsule(activity, hidden = false) } }
        }
        mainClock.advanceTimeBy(1_000)
        activity = SyncActivity.Idle
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Syncing with your other devices").assertExists()
    }
}
