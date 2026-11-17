package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.tenshu.core.Contains
import org.tenshu.core.Data
import org.tenshu.core.OneOf
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.core.dataFactory
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.Name
import org.tenshu.core.timeline.Moment
import org.tenshu.mia.MiaSource
import org.tenshu.mia.proxy.MiaChainValidator.FieldUse
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

    // ---- check 3: the field-use table, projections ----------------------------

    @Test
    fun aProjectionOutsideTheAllowlistIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val verdict = MiaChainValidator.validate(
            nameSearchChain(proxy),
            // Standing in for "the chain asked for File.Data": the allowlist fails closed,
            // so removing a permitted id is the same test as adding a forbidden one.
            policy.withoutUse(File.Field.PATH, FieldUse.PROJECT),
        )

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(verdict)
        assertTrue(refused.reason.startsWith("projection not allowed"), refused.reason)
    }

    // ---- check 3, the uses that are not projections ---------------------------
    //
    // ⚠⚠ Each of these PASSED before the field-use table existed: the validator compared only
    // `select` projections with anything (`core-semantic-permissions.md` §3.2).

    @Test
    fun aFilterOnAFieldThatMayNotBeFilteredIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(nameSearchChain(proxy), policy.withoutUse(File.Name.id, FieldUse.FILTER)),
        )
        assertEquals("filter field not allowed: ${File.Name.id}", refused.reason)
    }

    /**
     * ⚠⚠ The §3.2 hole itself: a field the policy lets a chain *return* is not thereby one it
     * may *search*. The existence read filters on the path, which [QueryPlanLowering.POLICY]
     * projects but never filters, so under the planner's policy it must not run even with its
     * op allowed.
     */
    @Test
    fun aProjectableFieldIsNotThereforeFilterable() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = QueryPlanLowering(proxy.tContext).lowerLiveFiles(listOf("/a.jpg"))

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(chain, policy.copy(allowedOperationIds = policy.allowedOperationIds + OneOf.ID)),
        )
        assertEquals("filter field not allowed: ${File.Field.PATH}", refused.reason)
        assertEquals(
            MiaChainValidator.Verdict.Allowed,
            MiaChainValidator.validate(chain, QueryPlanLowering.EXISTENCE_POLICY),
        )
    }

    @Test
    fun aSortKeyThatMayNotBeSortedByIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = QueryPlanLowering(proxy.tContext).lowerMomentsAtPlace("place-1", limit = 1)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(chain, policy.withoutUse(Moment.Fields.TIMESTAMP, FieldUse.SORT)),
        )
        assertEquals("sort key not allowed: ${Moment.Fields.TIMESTAMP}", refused.reason)
    }

    @Test
    fun aRelationThatMayNotBeTraversedIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(nameSearchChain(proxy), policy.withoutUse(Folder.Fields.FILES, FieldUse.TRAVERSE)),
        )
        assertEquals("traversal not allowed: ${Folder.Fields.FILES}", refused.reason)
    }

    /** A reference where a literal belongs: `name contains <the path field>`. */
    @Test
    fun aReferenceInAValuePositionIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = nameSearchChain(proxy).rewritePredicate(proxy) { step ->
            if (step is Operation && step.schema.id.get() == Contains.ID) {
                OperationDto(
                    params = step.params + (Contains.Params.OTHER to FieldReferenceDto(File.Field.PATH)),
                    schema = step.schema,
                )
            } else {
                step
            }
        }

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(MiaChainValidator.validate(chain, policy))
        assertEquals("reference in a value position: ${File.Field.PATH}", refused.reason)
    }

    @Test
    fun aValueAsAChainStepIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val take = nameSearchChain(proxy).filterIsInstance<Operation>().single { it.schema.id.get() == Take.ID }

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(nameSearchChain(proxy) + take.params.getValue(Take.Params.N), policy),
        )
        assertTrue(refused.reason.startsWith("not a chain step"), refused.reason)
    }

    // ---- check 1 inside a predicate -------------------------------------------

    /**
     * ⚠⚠ The proxy evaluates a predicate's own operations, so the read-only allowlist has to
     * reach inside it. Before the walk did, `contains` was on the allowlist and nothing read it.
     */
    @Test
    fun anOperationNestedInAPredicateIsCheckedLikeAnyOther() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(
                nameSearchChain(proxy),
                policy.copy(allowedOperationIds = policy.allowedOperationIds - Contains.ID),
            ),
        )
        assertEquals("operation not allowed: ${Contains.ID}", refused.reason)
    }

    // ---- grants on chains with no service reference ---------------------------

    /**
     * ⚠⚠ The calendar chain opens with an op on the service itself, so it carries no
     * `ServiceTypeReference` and the service check never sees it. Revoking CALENDAR has to
     * remove the op, or the grant is invisible to the validator.
     */
    @Test
    fun revokingTheCalendarRefusesTheCalendarChain() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val chain = QueryPlanLowering(proxy.tContext)
            .lowerCalendarOccurrences(LocalDate(2026, 11, 17), LocalDate(2026, 11, 18), limit = 5)
        val all = MiaSource.entries.toSet()

        assertEquals(MiaChainValidator.Verdict.Allowed, MiaChainValidator.validate(chain, QueryPlanLowering.policyFor(all)))
        val refused = assertIs<MiaChainValidator.Verdict.Refused>(
            MiaChainValidator.validate(chain, QueryPlanLowering.policyFor(all - MiaSource.CALENDAR)),
        )
        assertTrue(refused.reason.startsWith("operation not allowed"), refused.reason)
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

private fun MiaChainValidator.Policy.withoutUse(id: String, use: FieldUse): MiaChainValidator.Policy =
    copy(fieldUses = fieldUses + (id to (fieldUses[id].orEmpty() - use)))

/** Rewrites each step of the chain's single `filter` predicate. */
private fun List<Data>.rewritePredicate(
    proxy: org.tenshu.core.common.TestProxy,
    transform: (Data) -> Data,
): List<Data> = map { step ->
    if (step is Operation && step.schema.id.get() == Filter.ID) {
        val predicate = (step.params.getValue(Filter.Params.PREDICATE) as TList<*>).toList().filterIsInstance<Data>()
        OperationDto(
            params = step.params + (
                Filter.Params.PREDICATE to proxy.tContext.dataFactory().createList<Data>(predicate.map(transform))
                ),
            schema = step.schema,
        )
    } else {
        step
    }
}
