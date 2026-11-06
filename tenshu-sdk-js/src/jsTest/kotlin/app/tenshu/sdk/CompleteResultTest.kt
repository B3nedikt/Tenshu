package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.core.GenericError
import org.tenshu.core.InvalidParameter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Contacts.completeHandshake`'s failure mapping (threat model T-CI-10): the two typed refusals
 * reach the web as their own outcomes and catalogue keys, so the prompt can say "decline this
 * invite and send a new one" instead of "try again" — which it said to every `false` when this
 * returned a boolean.
 */
class CompleteResultTest : SdkRuntimeTest() {

    private suspend fun context() = TenshuSdk.awaitReady().await().let { JsRuntime.tContext }

    @Test
    fun aReplyThatIsNotTheOneShown_isItsOwnOutcome_withItsCatalogueKey() = runTest {
        val error = InvalidParameter.create(
            context(),
            name = CompleteContactHandshake.Params.INVITE_ID,
            reason = InvalidParameter.Reasons.ALREADY_CONSUMED,
        )
        val result = completeResultOf(error)
        assertEquals("responseChanged", result.outcome)
        assertEquals("error_invite_response_changed", result.message)
    }

    @Test
    fun aConfirmationWithNoCodeShown_isItsOwnOutcome_withItsCatalogueKey() = runTest {
        val error = InvalidParameter.create(
            context(),
            name = CompleteContactHandshake.Params.SAS_CONFIRMED,
            reason = InvalidParameter.Reasons.NOT_READY,
        )
        val result = completeResultOf(error)
        assertEquals("codeNotShown", result.outcome)
        assertEquals("error_invite_code_not_shown", result.message)
    }

    @Test
    fun anyOtherFailure_isFailed_withItsDerivedKey_neverItsMessage() = runTest {
        val result = completeResultOf(GenericError.create(context(), "proxy-side English"))
        assertEquals("failed", result.outcome)
        assertEquals("error_generic", result.message)
    }
}
