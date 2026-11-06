package org.tenshu.systemtest

import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The point of [valueOrFail] is the *message*, so that is what this pins.
 *
 * A test helper whose failure output is unhelpful is worse than none: it is what turned two
 * independent flakes into `ClassCastException: ResultFailureDto cannot be cast to
 * TResult$Success` and cost this investigation a fourteen-gate hunt to recover a cause that a
 * sibling test had been printing all along.
 */
class ResultAssertionsTest {

    private fun context() = TestProxy(schemaRegistry = createSyntheticTestRegistry()).tContext

    @Test
    fun aFailureNamesTheCallAndCarriesTheError() {
        val context = context()
        val result: TResult<org.tenshu.core.Data?> = TResult.Failure.create(
            context,
            GenericError.create(context, "401 Unauthorized — Token expired"),
        )

        val message = runCatching { result.valueOrFail("getPendingPairingRequests") }
            .exceptionOrNull()?.message.orEmpty()

        assertTrue(
            "getPendingPairingRequests" in message,
            "the message must name the call that failed; got: $message",
        )
        assertTrue(
            "Token expired" in message,
            "the message must carry the underlying error, which the old cast destroyed; got: $message",
        )
    }
}
