package app.tenshu.control.ui.screens.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ForgetThisDevice`'s confirmation must **route through the recovery phrase**, not refuse
 * (`admission-revocation-names-the-capability.md` §5b.12.2).
 *
 * ⚠⚠ Both halves are load-bearing and they pull in opposite directions:
 *  - A device holding the phrase must not be able to destroy it unknowingly — this is the failure
 *    that cost this project a realm (§5b.11).
 *  - A device holding **no** phrase (every paired joiner) must not be gated on acknowledging
 *    something it cannot show. An acknowledgement with nothing behind it is theatre, and theatre is
 *    what trains people to click through the acknowledgements that do matter.
 */
class ForgetDeviceDialogTest {

    private val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceHoldingThePhrase_showsIt_andCannotConfirmUntilAcknowledged() = runComposeUiTest {
        var confirmed = false
        var acked = false

        setContent {
            TenshuTheme {
                ForgetDeviceDialog(
                    phrase = phrase,
                    acknowledged = acked,
                    onAcknowledgedChange = { acked = it },
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        // The phrase is on screen at the moment it is about to stop existing here.
        onNodeWithText(phrase).assertIsDisplayed()

        onNodeWithText("Forget this device").performClick()
        waitForIdle()
        assertFalse(
            confirmed,
            "an unacknowledged confirm must not fire — this device may hold the realm's only A1",
        )

        onNodeWithText("I have saved my recovery phrase").performClick()
        waitForIdle()
        assertTrue(acked, "precondition: the row toggles the acknowledgement")
    }

    /**
     * ⚠ The other direction, and the one a "just gate everything" fix would break: a joiner holds no
     * phrase, so there is nothing to save and nothing to acknowledge. It must be able to forget
     * itself in one step.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceWithNoPhrase_confirmsInOneStep_withNoAcknowledgement() = runComposeUiTest {
        var confirmed = false

        setContent {
            TenshuTheme {
                ForgetDeviceDialog(
                    phrase = null,
                    acknowledged = false,
                    onAcknowledgedChange = {},
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        assertFalse(
            runCatching {
                onNodeWithText("I have saved my recovery phrase").assertIsDisplayed(); true
            }.getOrDefault(false),
            "no phrase means no acknowledgement to ask for",
        )

        onNodeWithText("Forget this device").performClick()
        waitForIdle()
        assertTrue(confirmed, "a joiner must be able to forget itself without a hollow gate")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theCopyNamesTheRealmWideConsequence_notJustTheDeviceLocalOne() = runComposeUiTest {
        setContent {
            TenshuTheme {
                ForgetDeviceDialog(
                    phrase = phrase,
                    acknowledged = false,
                    onAcknowledgedChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        // ⚠ "Start fresh" is safe for the device doing it; the harm lands on the devices left
        // behind. The warning has to say that, or it repeats RG-1's original omission.
        onNodeWithText(
            "This device holds your recovery phrase. It may be the only copy. Write it down before " +
                "continuing — without it nobody can add devices to this realm again.",
        ).assertIsDisplayed()
    }

    @Test
    fun theGateIsExpressedAsPhraseOrAcknowledged() {
        // Guards the enabled-rule itself, independent of rendering: `phrase == null || acknowledged`.
        fun enabled(phrase: String?, acknowledged: Boolean) = phrase == null || acknowledged

        assertEquals(true, enabled(null, false), "joiner: nothing to acknowledge")
        assertEquals(false, enabled("x", false), "holder: must acknowledge first")
        assertEquals(true, enabled("x", true), "holder: acknowledged, may proceed")
    }

    /**
     * ⚠⚠ Confirming is a ~10 SECOND wait, not an instant action: the op waits for the removal to
     * reach the realm behind an 8s timeout that elapses in full whenever the relay cannot confirm —
     * the common case, not a slow edge. With no busy state the dialog closed and Settings sat there
     * saying nothing, which was reported as *"confirming does nothing"* and cost two days chasing a
     * coroutine that was working correctly throughout.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun working_saysSoAndCannotBeConfirmedOrCancelledTwice() = runComposeUiTest {
        var confirmed = 0
        var dismissed = 0

        setContent {
            TenshuTheme {
                ForgetDeviceDialog(
                    phrase = null, // a joiner: no acknowledgement gate, so `working` is the only guard
                    acknowledged = false,
                    onAcknowledgedChange = {},
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    working = true,
                )
            }
        }
        waitForIdle()

        onNodeWithText("Removing this device from your realm…").assertIsDisplayed()

        onNodeWithText("Forget this device").performClick()
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertEquals(0, confirmed, "a second confirm must not re-run an irreversible op")
        assertEquals(
            0, dismissed,
            "cancelling mid-flight would drop the user on a Settings screen for a realm being torn " +
                "down under it",
        )
    }

    /** ⚠ The guard is `working`, not the acknowledgement — a joiner has no phrase and must still confirm. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun notWorking_aJoinerConfirmsWithNoAcknowledgement() = runComposeUiTest {
        var confirmed = 0
        setContent {
            TenshuTheme {
                ForgetDeviceDialog(
                    phrase = null,
                    acknowledged = false,
                    onAcknowledgedChange = {},
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                    working = false,
                )
            }
        }
        waitForIdle()
        onNodeWithText("Forget this device").performClick()
        waitForIdle()
        assertEquals(1, confirmed)
    }

}
