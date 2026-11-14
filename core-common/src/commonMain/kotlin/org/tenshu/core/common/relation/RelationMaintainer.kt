package org.tenshu.core.common.relation

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SaveOutcomeAwareDataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.isSubtypeOf
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tenshu.core.entity.getDataIdHash

/**
 * The one [DataObserver] that maintains **every** declared relation
 * (`core-relation-system.md` §9.2, phase R1).
 *
 * Relation membership is not a synced primitive: `SyncBlock`s carry entity data,
 * so each replica derives its relations locally from entity saves. That is what
 * ~30 near-identical hand-written maintainers were doing; this class is the same
 * function of the one parameter that actually differs ([RelationSpec.scope]),
 * driven from registration instead of code.
 *
 * **Membership converges probe-first** (R2b, `core-relation-system.md` §10.4):
 * the array is written only when it disagrees with the truth, so the steady state
 * — a member already present exactly once — writes nothing at all. Before R2b
 * this was a per-relation `MembershipOrder` choice, and the 30-odd relations that
 * chose remove-then-add were not choosing an order so much as inheriting the
 * simplest idempotent idiom; its two visible effects were that a re-saved member
 * jumped to the end of its array, and that the whole array row was rewritten
 * twice per save.
 *
 * The type match is **`isSubtypeOf`**, uniformly. Several of the hand-written
 * copies compared `type.get() != X.ID`, which silently dropped subtypes whose
 * rows key under the supertype anyway.
 *
 * Dispatch is one indexed lookup: [RelationRegistry] indexes specs by declared
 * element type, and the subtype closure of each *concrete* type encountered is
 * memoised here on first sight.
 */
@OptIn(ExperimentalAtomicApi::class)
class RelationMaintainer(
    private val executionContext: ExecutionContext,
    private val registry: RelationRegistry,
) : SaveOutcomeAwareDataObserver {

    /**
     * `concreteTypeId → matching specs`, filled lazily. Lock-free CAS for the
     * same reason `ObserverRegistry` uses one: observer fanout runs on
     * `Dispatchers.Default` workers, and a racing miss costs one redundant
     * subtype scan, never a wrong answer (the scan is pure).
     */
    private val resolved = AtomicReference<Map<String, List<RelationSpec>>>(emptyMap())

    /**
     * Only a type some relation keys on can reach the notification [redundant] gates, so only its
     * saves pay the store comparison (`SyncMetadata`, tombstones and other bookkeeping rows do not).
     */
    override fun wantsSaveOutcome(data: Data): Boolean = specsFor(typeIdOf(data)).isNotEmpty()

    private fun typeIdOf(data: Data): String = (data as? Entity<*>)?.type?.get() ?: data.id

    /**
     * [redundant] — every entry the save wrote was already stored byte-identically — silences only
     * the steady-state *notification* below, never a membership write (`core-deterministic-invalidation.md`
     * §8, P1). A redundant save cannot change what the relation resolves to, so re-evaluating every
     * stream over it is pure cost; the relay's echo of this device's own write is the case measured
     * (`SelfEchoRedundancySystemTest`). A peer's write, a rehydrating store's first sight of a row and
     * a slice-snapshot row are never redundant, because the stored row differs or is absent.
     */
    override fun onDataSaved(data: Data, origin: WriteOrigin, redundant: Boolean) {
        val specs = specsFor(typeIdOf(data))
        if (specs.isEmpty()) return

        // The element's identity comes from the registered `DataIdProvider`,
        // including the identity-root type resolution — no per-relation key
        // reader, and no chance of the maintainer keying a row differently from
        // the store that wrote it.
        val elementId = executionContext.getDataId(data) ?: return

        for (spec in specs) {
            val reference = referenceFor(spec, data) ?: continue
            // Atomic probe-then-repair: two `Dispatchers.Default` workers saving
            // the same entity concurrently could otherwise interleave
            // probe/probe/add/add and leave duplicates in the relation.
            executionContext.database.transaction {
                when (memberCount(reference, elementId)) {
                    // Steady state — already a member, exactly once. Nothing to
                    // write: the row itself was saved by the `saveData` that got
                    // us here, and membership is unchanged. Readers are still
                    // told, because a re-save that changed the row IS a change to
                    // what the relation resolves to (see
                    // `Database.notifyArrayMemberUpdated`) — and one that changed
                    // nothing is not.
                    1 -> if (!redundant) {
                        executionContext.database.notifyArrayMemberUpdated(
                            reference,
                            data,
                            origin,
                        )
                    }

                    // A genuinely new member. One write, not the remove-then-add
                    // pair the pre-R2b code did unconditionally — `removeFromArray`
                    // on an absent id rewrites the whole row to produce the row it
                    // just read.
                    0 -> executionContext.database.addToArray(reference, data)

                    // Duplicated: historical pollution from the era when two
                    // writers maintained one relation. Rebuild, so convergence to
                    // exactly-one is preserved.
                    else -> {
                        executionContext.database.removeFromArray(reference, elementId)
                        executionContext.database.addToArray(reference, data)
                    }
                }
            }
        }
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Only an element's own id can be a relation member; array ids and the
        // like are not. `removeFromArray` no-ops on an absent id, so no
        // membership lookup is needed first.
        if (dataId !is DataId.EntityDataId) return
        val specs = specsFor(dataId.type.get())
        if (specs.isEmpty()) return

        for (spec in specs) {
            val reference = referenceFor(spec, dataId) ?: continue
            executionContext.database.removeFromArray(reference, dataId)
        }
    }

    /**
     * [element] is the saved row on the save path and the deleted element's own
     * [DataId] on the delete path — see [RelationScope.EntityField]'s KDoc for
     * why the two collapse into one lambda.
     */
    private fun referenceFor(spec: RelationSpec, element: Data): ArrayDatabaseReference? {
        val parent = when (val scope = spec.scope) {
            is RelationScope.Service -> null
            is RelationScope.EntityField -> scope.parentOf(executionContext, element)
                ?: return null

            is RelationScope.EntityProperty -> scope.parentOf(executionContext, element)
                ?: return null
        }
        return registry.referenceFor(spec, executionContext.context, parent)
    }

    /**
     * How many times [elementId] appears in [reference]. Prefers the array's own
     * member-hash list (no member deserialisation); `null` there means
     * *unsupported/unknown*, never *empty*, so it falls back to a full read.
     */
    private fun memberCount(reference: ArrayDatabaseReference, elementId: DataId): Int {
        val hash = elementId.getDataIdHash()
        executionContext.database.getArrayMemberHashes(reference)
            ?.let { hashes -> return hashes.count { it == hash } }
        return executionContext.database.getArrayData(reference)
            ?.count { executionContext.getDataId(it)?.getDataIdHash() == hash }
            ?: 0
    }

    private fun specsFor(typeId: String): List<RelationSpec> {
        resolved.load()[typeId]?.let { return it }
        val schema = executionContext.schemaProvider.getSchema(typeId)
        val matches = if (schema == null) {
            emptyList()
        } else {
            registry.specs.filter { schema.isSubtypeOf(it.elementTypeId) }
        }
        while (true) {
            val current = resolved.load()
            current[typeId]?.let { return it }
            if (resolved.compareAndSet(current, current + (typeId to matches))) return matches
        }
    }
}

/**
 * Installs the single [RelationMaintainer] for this context, idempotently — the
 * "one observer, registered once" of §9.2. Every migrated `init<X>Service()`
 * calls it; the second and later calls are no-ops, so a proxy wiring several
 * migrated services still dispatches through exactly one observer.
 */
fun ExecutionContext.installRelationMaintainer() {
    if (observerRegistry.any { it is RelationMaintainer }) return
    addDataObserver(RelationMaintainer(this, relationRegistry))
}

/**
 * The [ArrayDatabaseReference] a **service** relation's members live in, derived
 * from the same [RelationSpec] the [RelationMaintainer] writes through — so a
 * reader and the writer cannot disagree about `isEntity` or `elementSchemaId`
 * (`core-relation-system.md` §9.2 / D4). Replaces the hand-built
 * `ArrayDatabaseReference(DataId.RelationDataId.create(…), isEntity = …,
 * elementSchemaId = …)` triples that used to sit at every reader.
 *
 * ⚠ **Fails loudly** on an undeclared [relationId]. The alternative — returning
 * `null` and letting the caller read an empty array — is exactly the failure
 * mode this helper exists to remove: a typo'd or unregistered id would read as
 * "the relation is empty", which is indistinguishable from the truth at the call
 * site and silently wrong.
 */
fun ExecutionContext.relationReference(relationId: String): ArrayDatabaseReference {
    val spec = relationRegistry.serviceRelationSpec(relationId)
        ?: error(
            "No relation declared for '$relationId'. A service relation is declared on its " +
                "service schema — `serviceDataRelation(id, type, maintained = true)`; " +
                "without that it has no maintenance entry and no reference to derive."
        )
    return relationRegistry.referenceFor(spec, context)
        ?: error("Relation '$relationId' resolved to no array reference")
}
