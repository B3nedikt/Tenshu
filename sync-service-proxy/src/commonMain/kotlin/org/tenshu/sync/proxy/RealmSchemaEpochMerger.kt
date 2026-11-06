package org.tenshu.sync.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.SemanticMerger

/**
 * Per-entry **MAX** merge for [RealmSchemaEpoch] — the resolution of
 * `core-schema-evolution.md` §12 open question 3 ("epoch record contention").
 *
 * MAX over a version vector is a textbook **join-semilattice**: idempotent,
 * commutative and associative, so two devices bumping different services (or
 * the same one) converge on the same row no matter which order the blocks
 * arrive in and no matter how often one is re-applied. That is what makes
 * §7.2's "bump on first write" safe without any coordination — racing bumps
 * cost a block, never correctness.
 *
 * A service present on only one side survives (the union is the join): a device
 * that never ran Fit must not erase the realm's Fit entry. `coreVersion` is
 * MAX'd the same way — it is the global floor (§4's caveat), so it may only
 * ever rise. Entries are emitted sorted by service id, matching
 * `currentVersionVector`, so the merged row is byte-deterministic.
 *
 * ⚠ **Registered in production wiring**, not only in tests: `initSyncService`
 * registers it into the `SemanticMergerRegistry` every platform's DI passes in
 * — the one place all of Android/desktop/iOS/JS/system-test funnel through. The
 * repo has a standing gotcha here (timeline's `MomentMerger`/`TripMerger` are
 * test-registered only), and an unregistered merger silently degrades this row
 * to last-writer-wins, which would *erase* a peer's bump of a different
 * service.
 *
 * ⚠ **Known bound, inherited from the merge seam:** `SyncProcessor` only
 * consults a merger when the incoming block is HLC-*newer* than the last
 * ingested state for the row; a strictly older block is dropped before reaching
 * here. So an epoch bump that loses the HLC race can be missed even though its
 * vector was not dominated. This can only *delay* fencing, never fence
 * wrongly — and it self-heals through ordinary use, because the bump trigger is
 * a **write**: the next time that device writes the service's data it finds its
 * own row behind and re-bumps (§7.2). Closing it properly means max-merging on
 * the stale branch too, which is a change to the shared conflict path rather
 * than to this merger.
 */
class RealmSchemaEpochMerger : SemanticMerger {

    override val typeId: String = RealmSchemaEpoch.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingVector = vectorOf(executionContext, existing)
        val incomingVector = vectorOf(executionContext, incoming)
        // A malformed side (no readable vector) must not erase the other's:
        // fall back to whichever we can read, else the incoming value verbatim.
        if (existingVector == null) return incoming
        if (incomingVector == null) return existing

        val merged = LinkedHashMap<String, Int>()
        (existingVector.toMap().entries + incomingVector.toMap().entries).forEach { (id, version) ->
            merged[id] = maxOf(merged[id] ?: version, version)
        }

        return RealmSchemaEpoch.create(
            context = executionContext.context,
            versions = VersionVector.create(
                context = executionContext.context,
                coreVersion = maxOf(
                    existingVector.coreVersion.get().toInt(),
                    incomingVector.coreVersion.get().toInt(),
                ),
                services = merged.entries
                    .sortedBy { it.key }
                    .map { (id, version) ->
                        ServiceVersion.create(executionContext.context, id, version)
                    },
            ),
        )
    }

    /**
     * The row's decoded vector, whether it arrived as a plain composite (the
     * ordinary case — [RealmSchemaEpoch] is saved as a composite, not an entity)
     * or was stored as an entity by some wiring. Mirrors `FolderMerger`'s
     * two-shape accessor discipline. `null` on unreadable bytes, so a corrupt
     * side can never erase the good one.
     */
    private fun vectorOf(executionContext: ExecutionContext, data: Data): VersionVector? {
        val bytes: TByteArray? = when (data) {
            is RealmSchemaEpoch -> data.versionsBytes
            is Entity<*> -> data.getFieldValue<TByteArray>(RealmSchemaEpoch.Fields.VERSIONS_BYTES)
            is Composite -> data.getOptionalField<TByteArray>(RealmSchemaEpoch.Fields.VERSIONS_BYTES)
            else -> null
        }
        val raw = bytes?.get() ?: return null
        return runCatching {
            BinaryParser(
                executionContext.context.schemaProvider(),
                executionContext.context.dataFactory(),
            ).parse<VersionVector>(raw)
        }.getOrNull()
    }
}
