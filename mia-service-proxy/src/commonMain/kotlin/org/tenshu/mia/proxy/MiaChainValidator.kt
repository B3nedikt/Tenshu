package org.tenshu.mia.proxy

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.ServiceTypeReference

/**
 * The one gate every chain Mia composes must pass (`mia-query-layer.md` MQ-D10).
 *
 * ⭐⭐ **Built in v1, while the only generator is our own grammar — which is precisely
 * when to build it.** In MQ-1 this should never reject anything [QueryPlanLowering]
 * emits; that is the point, not a sign it is doing nothing. A validator written while
 * you control the generator is one you can prove; a validator retrofitted around a model
 * is how a hole ships. When MQ-5 lets a model emit chains directly, the *generator*
 * changes and this boundary does not.
 *
 * It is a **pure function over the `List<Data>` callstack** — walkable because a chain is
 * already data, not code: each step is an `OperationDto` carrying its `OperationSchema`,
 * with nested predicates and selectors riding as `TList<Data>`. That is what makes this
 * cheap rather than heroic; a system that had to evaluate generated *code* could not do
 * this at all.
 *
 * The four checks, in order of how much they carry:
 *
 * 1. **Op allowlist — reads only.** ⚠⚠ Read-only is **not inherent** to the chain
 *    mechanism: the very same callstack shape carries every write in the platform. It is
 *    something this enforces, never something the DSL grants. Mutations reach the realm
 *    through MQ-D7's confirmation path in MQ-3, never by appearing in a query chain.
 * 2. **Service allowlist.** A chain touching a service the user has not granted is
 *    rejected *before* execution, not filtered afterwards.
 * 3. ⭐ **Projection allowlist — the stand-in for permissions we do not have.** Tenshu
 *    has no row-, field- or property-level capability system; grants are per *service*.
 *    An intent grammar never asks for content bytes implicitly, but a free-form chain can
 *    project `File.Data` or `File.Thumbnail` explicitly — so the projection is pinned to
 *    an **allowlist** (fails closed) rather than a denylist (fails open on the next
 *    property somebody adds). ⚠ Note the direction: more expressive querying *raises*
 *    the need for fine-grained permissions rather than being covered by the ones we have.
 * 4. **Cost bounds.** A hand-written intent has a known cost; a generated chain does not.
 *    Every chain must be bounded — by a `take` or by a terminal op that caps its own
 *    result — and bounded in depth and length.
 *
 * ⚠ **Subject identity is weaker than it looks.** This authenticates a *chain*, not a
 * *caller*: on desktop every app still registers as Drive (`UnixSocketServer` hardcodes
 * `clientId`), so per-service grants cannot yet be enforced against a reliably identified
 * subject there. Recorded so [Policy]'s service check is not read as stronger than it is;
 * closing it is app-registration work, not query-layer work.
 */
object MiaChainValidator {

    /**
     * What Mia is allowed to execute. In MQ-1 this is a constant
     * ([QueryPlanLowering.POLICY]); MQ-2 derives [allowedServiceIds] from the user's
     * revocable per-service grants, which is why it is a parameter and not a hardcoded
     * set inside the walk.
     */
    data class Policy(
        val allowedOperationIds: Set<String>,
        val allowedServiceIds: Set<String>,
        val allowedProjectionIds: Set<String>,
        /**
         * Ops that bound their own result via a param, e.g. `photosCapturedBetween` →
         * its `limit`. A chain ending in one of these needs no `take`.
         */
        val boundingOperations: Map<String, String>,
        val maxRows: Int,
        val maxSteps: Int,
        val maxNestingDepth: Int,
    )

    sealed interface Verdict {
        data object Allowed : Verdict

        /**
         * ⚠ [reason] is for the log and for tests, never for the user: it names internal
         * op and property ids. The user-facing surface is
         * [org.tenshu.mia.MiaAnswer.VERDICT_UNAVAILABLE].
         */
        data class Refused(val reason: String) : Verdict
    }

    fun validate(callstack: List<Data>, policy: Policy): Verdict {
        if (callstack.isEmpty()) return Verdict.Refused("empty callstack")
        if (callstack.size > policy.maxSteps) {
            return Verdict.Refused("chain of ${callstack.size} steps exceeds ${policy.maxSteps}")
        }

        var bounded = false

        callstack.forEach { step ->
            when (step) {
                is Operation -> {
                    val operationId = step.schema.id.get()
                    if (operationId !in policy.allowedOperationIds) {
                        return Verdict.Refused("operation not allowed: $operationId")
                    }
                    when (val boundVerdict = boundedBy(step, operationId, policy)) {
                        is Verdict.Refused -> return boundVerdict
                        Verdict.Allowed -> bounded = true
                        null -> Unit
                    }
                    projectionVerdict(step, policy)?.let { return it }
                    depthVerdict(step, policy)?.let { return it }
                }

                is ServiceTypeReference -> {
                    val serviceId = step.get()
                    if (serviceId !in policy.allowedServiceIds) {
                        return Verdict.Refused("service not granted: $serviceId")
                    }
                }
            }
        }

        if (!bounded) return Verdict.Refused("chain is unbounded: no take and no bounding operation")
        return Verdict.Allowed
    }

    /**
     * `Allowed` when this step bounds the result acceptably, `Refused` when it bounds it
     * too generously, `null` when it is not a bounding step at all.
     */
    private fun boundedBy(step: Operation, operationId: String, policy: Policy): Verdict? {
        val boundParam = when (operationId) {
            Take.ID -> Take.Params.N
            else -> policy.boundingOperations[operationId] ?: return null
        }
        val rows = (step.params[boundParam] as? TInteger)?.get()
            ?: return Verdict.Refused("bounding operation $operationId carries no $boundParam")
        if (rows <= 0 || rows > policy.maxRows) {
            return Verdict.Refused("bound of $rows on $operationId is outside 1..${policy.maxRows}")
        }
        return Verdict.Allowed
    }

    /** Check 3: every projected field/property id must be on the allowlist. */
    private fun projectionVerdict(step: Operation, policy: Policy): Verdict.Refused? {
        step.params.values.forEach { param ->
            projectedIds(param).forEach { id ->
                if (id !in policy.allowedProjectionIds) {
                    return Verdict.Refused("projection not allowed: $id")
                }
            }
        }
        return null
    }

    /**
     * Every field/property id a parameter projects, walking nested selector lists
     * (`relations(handle) { … }`). Non-selector params contribute nothing.
     */
    private fun projectedIds(data: Data): List<String> = when (data) {
        is Selector.FieldSelector<*> -> listOf(data.fieldReference.get())
        is Selector.PropertySelector<*> ->
            listOf(data.propertyReference.get()) +
                (data.nested?.toList().orEmpty().flatMap { projectedIds(it) })

        is Selector.DynamicPropertySelector<*> -> listOf(data.dynamicPropertyReference.get())
        is TList<*> -> data.toList().filterIsInstance<Data>().flatMap { projectedIds(it) }
        else -> emptyList()
    }

    /** Check 4's other half: bound how deeply nested predicate/selector lists may go. */
    private fun depthVerdict(step: Operation, policy: Policy): Verdict.Refused? {
        step.params.values.forEach { param ->
            val depth = nestingDepth(param)
            if (depth > policy.maxNestingDepth) {
                return Verdict.Refused("nesting depth $depth exceeds ${policy.maxNestingDepth}")
            }
        }
        return null
    }

    private fun nestingDepth(data: Data, current: Int = 1): Int = when (data) {
        is TList<*> -> data.toList()
            .filterIsInstance<Data>()
            .maxOfOrNull { nestingDepth(it, current + 1) } ?: current

        is Selector.PropertySelector<*> -> data.nested?.let { nestingDepth(it, current + 1) } ?: current
        else -> current
    }
}
