package app.tenshu.control.ui.screens.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.control.data.RemovedDevice
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.sync.common.PendingDeviceAccessRevocation
import kotlin.test.Test

/**
 * A removal the relay has not applied is never shown as plainly "removed" (maintainer decision 2026-11-12): the row
 * names the relay's answer — refused (and why), waiting, needs an update — or that the device was added back.
 */
@OptIn(ExperimentalTestApi::class)
class RemovedDeviceItemTest {

    private fun removed(state: String?, detail: String = "") =
        RemovedDevice("abcdef0123", removedAtMillis = 0L, removedItself = false, relayState = state, relayDetail = detail)

    @Test
    fun aRefusedRemoval_saysTheDeviceCanStillConnect_andWhy() = runComposeUiTest {
        setContent { TenshuTheme { RemovedDeviceItem(removed(PendingDeviceAccessRevocation.State.REFUSED, "401 Invalid revocation signature"), "Pixel 9", nowMs = 0L) } }
        onNodeWithText("Not removed at the relay", substring = true).assertIsDisplayed()
        onNodeWithText("401 Invalid revocation signature", substring = true).assertIsDisplayed()
    }

    @Test
    fun aProtocolSkew_andAnUnknownRealm_sayNotConfirmed() = runComposeUiTest {
        var state by androidx.compose.runtime.mutableStateOf(PendingDeviceAccessRevocation.State.NEEDS_UPDATE)
        setContent { TenshuTheme { RemovedDeviceItem(removed(state), null, nowMs = 0L) } }
        onNodeWithText("different versions", substring = true).assertIsDisplayed()
        state = PendingDeviceAccessRevocation.State.RELAY_UNKNOWN
        waitForIdle()
        onNodeWithText("doesn't know this account", substring = true).assertIsDisplayed()
    }

    @Test
    fun aSupersededRemoval_saysAddedBack() = runComposeUiTest {
        setContent { TenshuTheme { RemovedDeviceItem(removed(PendingDeviceAccessRevocation.State.SUPERSEDED), null, nowMs = 0L) } }
        onNodeWithText("Added back since", substring = true).assertIsDisplayed()
    }

    @Test
    fun anAppliedRemoval_addsNothing() = runComposeUiTest {
        setContent { TenshuTheme { RemovedDeviceItem(removed(null), null, nowMs = 0L) } }
        onAllNodesWithText("relay", substring = true).assertCountEquals(0)
    }
}
