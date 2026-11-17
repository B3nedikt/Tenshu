package org.tenshu.mia.proxy

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.Reference
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
 * 3. ⭐ **Field-use table — the stand-in for permissions we do not have.** Tenshu has no
 *    row-, field- or property-level capability system; grants are per *service*. Every
 *    field, property and relation a chain names is looked up in [Policy.fieldUses] **for
 *    the use its position gives it** ([FieldUse]): a top-level reference is a traversal, a
 *    reference inside a `filter` predicate is a filter, inside a `sortBy` selector a sort
 *    key, and a `select` selector a projection. An allowlist (fails closed), never a
 *    denylist (fails open on the next property somebody adds).
 *    ⚠⚠ **Filter is a read** (`core-semantic-permissions.md` SP-D3): `contains("a")`,
 *    `contains("ab")`, … reconstructs a field that was never projected, which is why this
 *    used to be a projection-only check with a hole in it. A field may be filtered only
 *    where it may be learned.
 *    ⚠ Note the direction: more expressive querying *raises* the need for fine-grained
 *    permissions rather than being covered by the ones we have.
 * 4. **Cost bounds.** A hand-written intent has a known cost; a generated chain does not.
 *    Every chain must be bounded — by a `take` or by a terminal op that caps its own
 *    result — and bounded in depth and length.
 *
 * ⚠⚠ **Checks 1–3 walk the whole tree, not the top-level steps.** A predicate is a nested
 * callstack with operations of its own (`contains`, `inRange`, …) and the proxy evaluates
 * them, so an op the allowlist does not name is refused wherever it appears — inside a
 * predicate exactly as at the top. Anything the walk cannot classify (a reference in a
 * literal position, a selector outside `select`, a bare list or value as a chain step) is
 * refused rather than skipped.
 *
 * ⚠ **Subject identity is weaker than it looks.** This authenticates a *chain*, not a
 * *caller*: on desktop every app still registers as Drive (`UnixSocketServer` hardcodes
 * `clientId`), so per-service grants cannot yet be enforced against a reliably identified
 * subject there. Recorded so [Policy]'s service check is not read as stronger than it is;
 * closing it is app-registration work, not query-layer work.
 */
object MiaChainValidator {

    /**
     * How a chain uses a field, by the position the reference sits in
     * (`core-semantic-permissions.md` SP-D3).
     */
    enum class FieldUse {
        /** Returned to the caller: a `select` selector. */
        PROJECT,

        /** ⚠⚠ Compared inside a `filter` predicate — and therefore learnable (SP-D3). */
        FILTER,

        /** ⚠ Ordered by in `sortBy` — a comparison oracle, so still a read. */
        SORT,

        /** Walked to reach rows: a top-level relation step (`root`, `files`, `moments`). */
        TRAVERSE,
    }

    /**
     * What Mia is allowed to execute. In MQ-1 this is a constant
     * ([QueryPlanLowering.POLICY]); MQ-2 derives [allowedServiceIds] from the user's
     * revocable per-service grants, which is why it is a parameter and not a hardcoded
     * set inside the walk.
     */
    data class Policy(
        val allowedOperationIds: Set<String>,
        val allowedServiceIds: Set<String>,
        /**
         * Field, property and relation id → the uses a chain may make of it. Ids are
         * composite-qualified (`tenshu/timeline/moment/timestamp`), so this is per composite.
         * An id that is absent may not be named at all.
         */
        val fieldUses: Map<String, Set<FieldUse>>,
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
                    when (val boundVerdict = boundedBy(step, operationId, policy)) {
                        is Verdict.Refused -> return boundVerdict
                        Verdict.Allowed -> bounded = true
                        null -> Unit
                    }
                    walk(step, use = null, policy)?.let { return it }
                    depthVerdict(step, policy)?.let { return it }
                }

                is Reference -> walk(step, use = FieldUse.TRAVERSE, policy)?.let { return it }

                else -> return Verdict.Refused("not a chain step: ${step.schema.id.get()}")
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

    /**
     * Checks 1–3 over one node and everything under it. [use] is what a reference found
     * here would be used for, given by the enclosing operation's parameter; `null` means a
     * literal position, where no reference belongs.
     */
    private fun walk(data: Data, use: FieldUse?, policy: Policy): Verdict.Refused? {
        when (data) {
            is Operation -> {
                val operationId = data.schema.id.get()
                if (operationId !in policy.allowedOperationIds) {
                    return Verdict.Refused("operation not allowed: $operationId")
                }
                data.params.forEach { (paramId, value) ->
                    walk(value, useOf(paramId), policy)?.let { return it }
                }
            }

            is ServiceTypeReference -> {
                val serviceId = data.get()
                if (serviceId !in policy.allowedServiceIds) {
                    return Verdict.Refused("service not granted: $serviceId")
                }
            }

            is Reference -> {
                val id = data.get()
                if (use == null) return Verdict.Refused("reference in a value position: $id")
                if (use !in policy.fieldUses[id].orEmpty()) return refusal(use, id)
            }

            is Selector.FieldSelector<*> ->
                return projection(data.fieldReference.get(), use, policy)

            is Selector.PropertySelector<*> -> {
                projection(data.propertyReference.get(), use, policy)?.let { return it }
                data.nested?.let { nested -> walk(nested, use, policy)?.let { return it } }
            }

            is Selector.DynamicPropertySelector<*> ->
                return projection(data.dynamicPropertyReference.get(), use, policy)

            is TList<*> -> data.toList().filterIsInstance<Data>().forEach { child ->
                walk(child, use, policy)?.let { return it }
            }

            // A literal: a string, a number, a boolean. Nothing to check.
            else -> Unit
        }
        return null
    }

    private fun projection(id: String, use: FieldUse?, policy: Policy): Verdict.Refused? = when {
        use != FieldUse.PROJECT -> Verdict.Refused("selector outside select: $id")
        FieldUse.PROJECT !in policy.fieldUses[id].orEmpty() -> refusal(FieldUse.PROJECT, id)
        else -> null
    }

    /**
     * The use a reference inside this parameter gets. ⚠ Only these four positions take a
     * reference at all; every other parameter is a literal, and a reference there is refused.
     */
    private fun useOf(paramId: String): FieldUse? = when (paramId) {
        Filter.Params.PREDICATE -> FieldUse.FILTER
        SortBy.Params.SELECTOR, SortBy.Params.THEN_SELECTORS -> FieldUse.SORT
        Select.Params.SELECTORS -> FieldUse.PROJECT
        else -> null
    }

    private fun refusal(use: FieldUse, id: String) = Verdict.Refused(
        when (use) {
            FieldUse.PROJECT -> "projection not allowed: $id"
            FieldUse.FILTER -> "filter field not allowed: $id"
            FieldUse.SORT -> "sort key not allowed: $id"
            FieldUse.TRAVERSE -> "traversal not allowed: $id"
        },
    )

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
