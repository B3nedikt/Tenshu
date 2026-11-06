package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.sync.common.CompactionReceipt

/**
 * Re-emits a service's current state as fresh blocks at the current version
 * (E4-3b, `core-schema-evolution.md` §8.3/§8.3.4) — the mechanism the readable
 * floor eventually advances behind.
 *
 * ## Why this is an ordinary write, not a new emit path
 *
 * Compaction is **maintenance, not a protocol**. Every row is decoded, re-keyed
 * to its own `DataId` and handed to `saveData`, so the whole ordinary emit path
 * runs: relation maintenance, the realm-epoch bump, lane batching, the pending
 * journal. Re-emission is *idempotent and convergent* (§8.3) — two devices
 * racing produce redundant blocks that merge by the usual rules — so a second
 * write path with different side effects would buy nothing and could diverge.
 *
 * ⚠ §8.3.4 previously claimed this was structurally hard because a `DataId`
 * cannot be recovered from a hash-keyed store. That was wrong: `DataId`s are
 * **derived, not stored**, and [ExecutionContext.getDataId] recomputes one by
 * running the type's `DataIdProvider` over the row's own fields. The
 * not-invertible constraint is real for a *queued block* (E2f — a hash plus an
 * opaque ciphertext, nothing to re-derive from) and does not transfer to a
 * plaintext row.
 *
 * ## The epoch interaction, resolved
 *
 * ⚖️ Re-emitting through `saveData` can raise the realm epoch, and that is
 * **correct rather than a side effect to suppress**: the blocks really are
 * written at the current version, so a peer that cannot read them really should
 * be fenced (§7.2/§7.3). In practice the epoch is usually already there — the
 * first ordinary write after an upgrade bumps it long before a maintenance pass
 * runs — so this is a no-op in the common case and honest in the rest.
 *
 * ## What it will not do
 *
 * ⚠ **A row whose type has no `DataIdProvider` is skipped with a named line**,
 * never guessed at: without a provider there is no key to re-derive, and
 * inventing one would write a *new* row rather than re-emit the existing one.
 * ⚠ **A store that cannot enumerate is refused outright** ([ObjectStore.rowsOfTypes]
 * returning null), and must never be read as "no rows": writing a receipt for
 * work that did not happen would make every peer skip it too.
 */
class SchemaCompactor(
    private val executionContext: ExecutionContext,
    private val store: ObjectStore,
    private val mapper: DbEntryMapper,
) {

    sealed interface Outcome {
        /** [reEmitted] rows re-emitted; [skipped] had no derivable `DataId`. */
        data class Compacted(val reEmitted: Int, val skipped: Int) : Outcome

        /** The store cannot enumerate — nothing was done and no receipt was written. */
        data object Unsupported : Outcome
    }

    /**
     * @param typeIds the service's own types. Passed in rather than derived here:
     *   which types a service owns is a registry question (`owningServiceOf`) and
     *   belongs with the caller that also decides *whether* to compact
     *   ([org.tenshu.sync.common.CompactionPolicy]).
     * @param atVersion the version being compacted **to** — recorded on the
     *   receipt, so a later reader can tell whether the work is still current.
     */
    fun compact(
        serviceId: String,
        typeIds: Set<String>,
        atVersion: Int,
        deviceId: String,
        realmPrefix: String = "",
    ): Outcome {
        val rows = store.rowsOfTypes(typeIds) ?: return Outcome.Unsupported
        if (rows.isEmpty()) {
            // Nothing to re-emit, but the service IS at this version, so record it:
            // a peer must not keep re-attempting an empty service forever.
            writeReceipt(serviceId, atVersion, deviceId, realmPrefix)
            return Outcome.Compacted(reEmitted = 0, skipped = 0)
        }

        var reEmitted = 0
        var skipped = 0
        for (row in rows) {
            // ⚠ `getEntry` is not optional plumbing: store rows reference nested
            // composites BY HASH rather than inlining them (E2b, §8.6), so
            // materialising a row means following those references back into the
            // store. A missing child is a corrupt store, and the row is skipped
            // below rather than allowed to abort the whole pass.
            val data = runCatching {
                mapper.deSerialize(row, getEntry = { hash ->
                    store.getObject(hash) ?: error("missing nested row $hash")
                })
            }.getOrNull()
            if (data == null) {
                skipped++
                println(
                    "SchemaCompactor: '$serviceId' skipped a '${row.type}' row " +
                        "(hash=${row.dataIdHash}) — it did not decode under the live schema."
                )
                continue
            }
            if (executionContext.getDataId(data) == null) {
                skipped++
                println(
                    "SchemaCompactor: '$serviceId' skipped a '${row.type}' row " +
                        "(hash=${row.dataIdHash}) — no DataIdProvider, so its key cannot be re-derived."
                )
                continue
            }
            executionContext.saveData(data)
            reEmitted++
        }

        writeReceipt(serviceId, atVersion, deviceId, realmPrefix)
        println(
            "SchemaCompactor: '$serviceId' compacted to v$atVersion — " +
                "$reEmitted re-emitted, $skipped skipped."
        )
        return Outcome.Compacted(reEmitted, skipped)
    }

    /**
     * ⚠ Written **after** the work, not before. §8.3 describes the receipt as
     * written by "a device intending to compact", but intent-first would let a
     * crash mid-pass leave a receipt claiming completion, and every peer would
     * then skip work nobody did. Completion-first costs at most a redundant
     * concurrent pass — which §8.3 already accepts as the price of having no
     * election — and can never under-report.
     */
    private fun writeReceipt(
        serviceId: String,
        atVersion: Int,
        deviceId: String,
        realmPrefix: String,
    ) {
        executionContext.saveData(
            CompactionReceipt.create(
                context = executionContext.context,
                serviceId = serviceId,
                compactedToVersion = atVersion,
                compactedBy = deviceId,
                realmPrefix = realmPrefix,
            )
        )
    }
}
