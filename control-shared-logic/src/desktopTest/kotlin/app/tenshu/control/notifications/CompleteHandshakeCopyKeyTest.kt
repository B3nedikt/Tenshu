package app.tenshu.control.notifications

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.core.GenericError
import org.tenshu.core.InvalidParameter
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Control's notification card accepts a redeemed contact invite through
 * `TenshuPendingRequestsRepository.confirmInvite`. The two typed refusals of threat model T-CI-10
 * must come out as their own catalogue keys there — the derived key for an `InvalidParameter` is
 * the generic sentence, which tells the user nothing about declining and re-inviting.
 */
class CompleteHandshakeCopyKeyTest {

    private val context = TestProxy(schemaRegistry = createCoreTestRegistry()).tContext

    private fun invalid(parameter: String, reason: String) =
        InvalidParameter.create(context, name = parameter, reason = reason)

    @Test
    fun theReplyThatIsNotTheOneShown_hasItsOwnKey() {
        val error = invalid(
            CompleteContactHandshake.Params.INVITE_ID,
            InvalidParameter.Reasons.ALREADY_CONSUMED,
        )
        assertEquals(ErrorCopyKey.INVITE_RESPONSE_CHANGED, error.completeHandshakeCopyKey())
    }

    @Test
    fun aConfirmationWithNoCodeShown_hasItsOwnKey() {
        val error = invalid(
            CompleteContactHandshake.Params.SAS_CONFIRMED,
            InvalidParameter.Reasons.NOT_READY,
        )
        assertEquals(ErrorCopyKey.INVITE_CODE_NOT_SHOWN, error.completeHandshakeCopyKey())
    }

    @Test
    fun theSameReasonOnAnotherParameter_andEveryOtherError_keepTheDerivedKey() {
        // The match is on (parameter, reason) together: `ALREADY_CONSUMED` on some other
        // parameter is a different failure and must not borrow this copy.
        val elsewhere = invalid("tenshu/other/op/param", InvalidParameter.Reasons.ALREADY_CONSUMED)
        assertEquals(elsewhere.errorCopyKey(), elsewhere.completeHandshakeCopyKey())

        val swapped = invalid(
            CompleteContactHandshake.Params.INVITE_ID,
            InvalidParameter.Reasons.NOT_READY,
        )
        assertEquals(swapped.errorCopyKey(), swapped.completeHandshakeCopyKey())

        val generic = GenericError.create(context, "boom")
        assertEquals(generic.errorCopyKey(), generic.completeHandshakeCopyKey())
    }
}
