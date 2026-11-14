package app.tenshu.ui.feedback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.reachability.UnreachableReason
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.storage.common.RealmStorageState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SuiteSyncBannerTest {

    /**
     * ⚠⚠ The state the 2026-11-12 phone was in, said in the app the user was looking at — and only
     * that: one strip, never the app's own Control or storage banner beside it.
     */
    @Test
    fun aDeviceTheRelayNoLongerAccepts_isSaidInPlainWords() = runComposeUiTest {
        setContent {
            TenshuTheme {
                SuiteSyncBanner(
                    problem = SuiteSyncProblem.DeviceUnrecognized,
                    controlBanner = { Text("CONTROL BANNER") },
                    storageBanner = { Text("STORAGE BANNER") },
                )
            }
        }
        onNodeWithText("This device isn't connected to your account any more", substring = true)
            .assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("CONTROL BANNER").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("STORAGE BANNER").fetchSemanticsNodes().size)
    }

    /** Control and storage keep the app's own words; the strip only chooses which one shows. */
    @Test
    fun controlAndStorage_areTheAppsOwnBanners() = runComposeUiTest {
        var problem: SuiteSyncProblem by mutableStateOf(
            SuiteSyncProblem.Control(UnreachableReason.Transport),
        )
        setContent {
            TenshuTheme {
                SuiteSyncBanner(
                    problem = problem,
                    controlBanner = { Text("CONTROL BANNER") },
                    storageBanner = { Text(if (it is RealmStorageState.NoPlan) "NO PLAN" else "FULL") },
                )
            }
        }
        onNodeWithText("CONTROL BANNER").assertIsDisplayed()
        problem = SuiteSyncProblem.NoPlan
        waitForIdle()
        onNodeWithText("NO PLAN").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("CONTROL BANNER").fetchSemanticsNodes().size)
    }

    /** No problem, no strip: an always-on bar is one people learn to ignore. */
    @Test
    fun noProblem_drawsNothing() = runComposeUiTest {
        setContent {
            TenshuTheme {
                SuiteSyncBanner(
                    problem = SuiteSyncProblem.None,
                    controlBanner = { Text("CONTROL BANNER") },
                    storageBanner = { Text("STORAGE BANNER") },
                )
            }
        }
        assertEquals(0, onAllNodesWithText("BANNER", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Offline", substring = true).fetchSemanticsNodes().size)
    }
}
