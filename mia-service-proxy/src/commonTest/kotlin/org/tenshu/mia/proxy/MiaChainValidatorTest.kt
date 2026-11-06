package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.Take
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.mia.plan.QueryPlan
import org.tenshu.mia.plan.TimeWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MQ-D10's four checks, each proved to actually refuse.
 *
 * ⚠⚠ **A validator only tested on the positive side is indistinguishable from `return
 * Allowed`.** Every case here removes exactly one thing from the policy the planner's own
 * chain satisfies, and asserts the refusal names it — so a check that silently stopped
 * working fails here rather than the day a model starts generating chains.
 */
class MiaChainValidatorTest {

    private val policy = QueryPlanLowering.POLICY

    private suspend fun nameSearchChain(proxy: org.tenshu.core.common.TestProxy): List<Data> =
        QueryPlanLowering(proxy.tContext)
            .lower(QueryPlan.FindFilesNamed(text = "invoice", limit = 5, explanation = ""))!!

    private suspend fun photoChain(proxy: org.tenshu.core.common.TestProxy): List<Data> =
        QueryPlanLowering(proxy.tContext)
            .lower(QueryPlan.FindPhotos(TimeWindow(0L, 1L, "w"), limit = 5, explanation = ""))!!

    // ---- check 1: op allowlist -------------------------------------------------

    @Test
    fun anOperationOutsideTheAllowlistIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = nameSearchChain(proxy)

        val verdict = MiaChainValidator.validate(
            chain,
            // ⚠⚠ This is the read-only boundary. The same callstack shape carries every
            // write in the platform, so dropping an op from the list must be the thing
            // that stops it — not a property of the DSL.
            policy.copy(allowedOperationIds = policy.allowedOperationIds - "tenshu/core/collection/list/filter"),
        )

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(verdict)
        assertTrue(refused.reason.contains("filter"), refused.reason)
    }

    // ---- check 2: service allowlist --------------------------------------------

    @Test
    fun aChainTouchingAnUngrantedServiceIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val verdict = MiaChainValidator.validate(
            nameSearchChain(proxy),
            policy.copy(allowedServiceIds = emptySet()),
        )

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(verdict)
        assertTrue(refused.reason.startsWith("service not granted"), refused.reason)
    }

    // ---- check 3: projection allowlist -----------------------------------------

    @Test
    fun aProjectionOutsideTheAllowlistIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val verdict = MiaChainValidator.validate(
            nameSearchChain(proxy),
            // Standing in for "the chain asked for File.Data": the allowlist fails closed,
            // so removing a permitted id is the same test as adding a forbidden one.
            policy.copy(allowedProjectionIds = policy.allowedProjectionIds - "tenshu/file/path"),
        )

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(verdict)
        assertTrue(refused.reason.startsWith("projection not allowed"), refused.reason)
    }

    // ---- check 4: cost bounds --------------------------------------------------

    @Test
    fun anUnboundedChainIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = nameSearchChain(proxy)
            .filterNot { it is Operation && it.schema.id.get() == Take.ID }

        val verdict = MiaChainValidator.validate(chain, policy)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(verdict)
        assertTrue(refused.reason.contains("unbounded"), refused.reason)
    }

    @Test
    fun aBoundBeyondTheCeilingIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = nameSearchChain(proxy).map { step ->
            if (step is Operation && step.schema.id.get() == Take.ID) {
                OperationDto(
                    params = mapOf(
                        Take.Params.N to (QueryPlanLowering.MAX_ROWS + 1L).toTInteger(proxy.tContext),
                    ),
                    schema = step.schema,
                )
            } else {
                step
            }
        }

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(chain, policy),
        )
        assertTrue(refused.reason.contains("outside"), refused.reason)
    }

    @Test
    fun aChainLongerThanTheStepCeilingIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = nameSearchChain(proxy)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(chain, policy.copy(maxSteps = 2)),
        )
        assertTrue(refused.reason.contains("steps"), refused.reason)
    }

    @Test
    fun anEmptyChainIsRefused() {
        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(emptyList(), policy),
        )
        assertEquals("empty callstack", refused.reason)
    }

    // ---- the bounding-operation path -------------------------------------------

    @Test
    fun anOpThatBoundsItsOwnResultNeedsNoTake() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        // `photosCapturedBetween` caps itself via `limit`, so its chain is bounded with no
        // `take` in sight — which is why the bound is a policy entry rather than a search
        // for one specific operation id.
        assertEquals(
            MiaChainValidator.Verdict.Allowed,
            MiaChainValidator.validate(photoChain(proxy), policy),
        )
    }

    @Test
    fun aBoundingOpWithNoDeclaredBoundParamIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(
                photoChain(proxy),
                policy.copy(
                    boundingOperations = mapOf(
                        "tenshu/file/system/photosCapturedBetween" to "a/param/that/does/not/exist",
                    ),
                ),
            ),
        )
        assertTrue(refused.reason.contains("carries no"), refused.reason)
    }
}
