package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.mia.plan.QueryPlan
import org.tenshu.mia.plan.TimeWindow
import org.tenshu.inference.InferenceService
import org.tenshu.inference.PhotosLike
import org.tenshu.mia.MiaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MQ-D3's claim, asserted on the artifact rather than on its effects: a plan really does
 * become an ordinary callstack, and the callstack really is walkable.
 */
class QueryPlanLoweringTest {

    private fun lowering(proxy: org.tenshu.core.common.TestProxy) = QueryPlanLowering(proxy.tContext)

    @Test
    fun aNameSearchLowersToTheOrdinaryFilterChain() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val chain = lowering(proxy).lower(
            QueryPlan.FindFilesNamed(text = "invoice", limit = 5, explanation = ""),
        )!!

        assertEquals(
            listOf(
                "tenshu/core/collection/list/traverse",
                "tenshu/core/collection/list/filter",
                "tenshu/core/collection/list/take",
                "tenshu/core/collection/list/select",
                "tenshu/core/collection/list/getAll",
            ),
            chain.filterIsInstance<Operation>().map { it.schema.id.get() },
        )
    }

    /**
     * ⚠⚠ **The regression this test exists for.** `FilterRo` builds its predicate on a
     * *derived* chain and then appends its own step to the original, which is only correct
     * when the chain copies on write. Lowering through `SimpleRequestChain` (as
     * `CallstackMaterializer` does) mutates instead, stranding the predicate's `TList` as
     * a top-level step — and execution then dies with the thoroughly unhelpful
     * `Could not handle protocol: tenshu/core/list`. Everything above still passed while
     * that was broken; only this shape assertion says why.
     */
    @Test
    fun noPredicateListIsStrandedAtTheTopLevelOfTheChain() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val chain = lowering(proxy).lower(
            QueryPlan.FindFilesNamed(text = "invoice", limit = 5, explanation = ""),
        )!!

        assertTrue(
            chain.none { it is TList<*> },
            "a predicate rides inside its operation's params, never as a chain step: " +
                chain.map { it::class.simpleName },
        )
    }

    @Test
    fun thePhotoPlanLowersToTheShippedOpWithItsWindowAndBound() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val chain = lowering(proxy).lower(
            QueryPlan.FindPhotos(TimeWindow(100L, 200L, "a window"), limit = 7, explanation = ""),
        )!!

        val op = chain.filterIsInstance<Operation>().single()
        assertEquals("tenshu/file/system/photosCapturedBetween", op.schema.id.get())
        assertEquals(7L, (op.params["tenshu/file/system/photosCapturedBetween/limit"] as TInteger).get())
    }

    @Test
    fun everyLoweredChainCarriesItsBound() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val named = lowering(proxy).lower(
            QueryPlan.FindFilesNamed(text = "x", limit = 5, explanation = ""),
        )!!

        val take = named.filterIsInstance<Operation>().single { it.schema.id.get() == Take.ID }
        assertEquals(5L, (take.params[Take.Params.N] as TInteger).get())
    }

    @Test
    fun aNoMatchLowersToNothingAtAll() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        assertNull(lowering(proxy).lower(QueryPlan.NoMatch("nope")))
    }

    /**
     * The chains the planner actually emits must pass the policy — MQ-D10's "in v1 it
     * should never reject anything the planner emits" stated as a test rather than as a
     * hope.
     */
    @Test
    fun everyPlannerChainPassesTheValidator() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val plans = listOf<QueryPlan>(
            QueryPlan.FindFilesNamed(text = "invoice", limit = 5, explanation = ""),
            QueryPlan.FindPhotos(TimeWindow(0L, 1L, "w"), limit = 5, explanation = ""),
        )

        plans.forEach { plan ->
            val chain: List<Data> = lowering(proxy).lower(plan)!!
            assertEquals(
                MiaChainValidator.Verdict.Allowed,
                MiaChainValidator.validate(chain, QueryPlanLowering.POLICY),
                "planner emitted a chain its own policy refuses: $plan",
            )
        }
    }

    @Test
    fun revokingVisualSearchRemovesTheOpFromThePolicy() {
        val all = MiaSource.entries.toSet()

        assertTrue(PhotosLike.ID in QueryPlanLowering.policyFor(all).allowedOperationIds)
        assertTrue(
            PhotosLike.ID !in QueryPlanLowering.policyFor(all - MiaSource.VISUAL_SEARCH).allowedOperationIds,
        )
    }

    @Test
    fun theLabelsGrantAloneDoesNotOpenTheVisualSearchOp() {
        // ⚠⚠ The check the SERVICE allowlist cannot make. `LABELS` and `VISUAL_SEARCH` share
        // `InferenceService`, so with only the first granted the service check still passes — and
        // before MQ-4 narrowed the op set, that was the *whole* validator opinion. The op is the
        // only thing that tells the two chains apart.
        val labelsOnly = setOf(MiaSource.LABELS)
        val policy = QueryPlanLowering.policyFor(labelsOnly)

        assertTrue(
            InferenceService.ID in policy.allowedServiceIds,
            "the service is reachable on the labels grant alone, which is the point",
        )
        assertTrue(PhotosLike.ID !in policy.allowedOperationIds)
    }
}
