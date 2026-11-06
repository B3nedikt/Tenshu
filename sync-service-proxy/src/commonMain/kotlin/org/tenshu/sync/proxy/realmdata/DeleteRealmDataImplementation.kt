package org.tenshu.sync.proxy.realmdata

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.impl.NotFoundErrorDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.realmdata.DeleteRealmData
import org.tenshu.core.trait.RealmData
import org.tenshu.sync.common.TombstoneEmitter

/**
 * `DeleteRealmData` (`docs/proposals/control-data-reset.md` R1): delete every row of
 * one [RealmData] type, realm-wide, optionally only rows created before a cut-off.
 *
 * ### ⚠⚠ Why this cannot be a `deleteData` loop
 *
 * `SyncObserver.onDataDeleted` is deliberately a **no-op** — tombstones are emitted
 * *explicitly* by operation implementations so that a cascade delete inside one
 * operation does not produce one tombstone per child. A bulk delete written as a bare
 * loop over `ExecutionContext.deleteData` therefore deletes on **this device only**:
 * every peer keeps its rows, and the next pull carries them straight back. The user
 * watches their data reappear. So each top-level row deleted here emits **exactly one**
 * tombstone, and the cascade maintainers reached through `deleteData`'s observer fanout
 * remove children silently — the same contract `DeleteFileImplementation` follows (§3).
 *
 * ⚠ Order matters, and it is remove-from-array *then* delete: a row deleted while still
 * listed in its relation array leaves a dangling member — an id that resolves to
 * nothing on every subsequent read of that array.
 *
 * ### ⚠⚠ Why it is async, batched, and tombstones *first* (`control-data-reset.md` §11)
 *
 * The v1 shape ANR'd Control on a real store (~10k `Workout` rows). Three
 * separate causes, all fixed here:
 *
 *  - **It ran on the caller's thread.** A [SyncOperationImplementation] is
 *    invoked inline by `ExecutionContext.execute`, and nothing in the
 *    in-process path (Control's own UI → `viewModelScope` → `LocalTaskExecutor`)
 *    hops dispatcher — so a multi-minute loop ran on the main thread despite the
 *    op declaring `IoOperation`. Async, like `DeleteFileImplementation`.
 *  - **It was quadratic, twice.** `removeFromArray` and the tombstone queue's
 *    `addToArray` each rewrite their whole array per row, so N rows cost N array
 *    rewrites over an N-sized list — on both sides. Both now go through the bulk
 *    primitives ([org.tenshu.core.common.proxy.Database.removeAllFromArray] /
 *    [org.tenshu.sync.common.TombstoneEmitter.emitAll]), one write per chunk.
 *  - **A kill mid-run could diverge silently.** Deleting a row locally *before*
 *    its tombstone was durably queued loses the only handle on it: the id is
 *    gone from this device, so a re-run cannot tombstone it and every peer keeps
 *    the row forever. Tombstones are therefore queued **first** — the queue is
 *    durable, so a kill after it leaves a delete that still converges, and a
 *    kill before it leaves rows a re-run finds again. (Replaying the queue to
 *    finish the *local* half without a re-tap is the next slice; today the
 *    residue is a row this device still shows, which the same button removes.)
 *
 * Work is chunked so cancellation is responsive: the caller's `TaskHandle`
 * cancel (or a cleared ViewModel) unwinds at the next chunk boundary, leaving a
 * consistent partial delete — fewer rows, all of them tombstoned.
 *
 * ### ⚠⚠ Why it lives in `:sync-service-proxy` and not `:core-common`
 *
 * [TombstoneEmitter] is declared in `:sync-service-common`, which depends on
 * `:core-common` — so a `:core-common` implementation would be a dependency cycle.
 * That is not merely a build accident: emitting the tombstone *is* the operation's
 * substance (see above), so it belongs beside the tombstone machinery. The op's
 * schema stays in `:core` (it is generic over the core-level `RealmData` category)
 * and its client Ro in `:core-common`, exactly as for the `AppService` ops.
 *
 * @param tombstoneEmitter `null` (test harnesses, pre-sync wirings) still deletes the
 *   **local** rows — only the cross-device propagation is skipped. Same posture as
 *   `DeleteFileImplementation`. ⚠ A production wiring that forgets it converges
 *   nowhere, which is invisible on one device.
 */
internal class DeleteRealmDataImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : AsyncOperationImplementation {

    private companion object {
        /**
         * Rows per array write / tombstone batch. Bounds both the cancellation
         * latency and the size of one store write; the win over the per-row
         * shape is already asymptotic, so a bigger chunk buys little.
         *
         * ⚠ Deliberately NOT lowered to shorten lock holds — each chunk rewrites
         * the relation array whole, so a smaller chunk means *more* full-array
         * writes. Lock fairness is bought by [PACE_EVERY] / [PACE_MS] instead,
         * which cost nothing per row.
         */
        const val CHUNK = 250

        /** Deletes between yields — see the pacing note at the call site. */
        const val PACE_EVERY = 25

        /** Gap between chunks, in which this coroutine holds no lock at all. */
        const val PACE_MS = 10L
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val typeId: String = params.read<TString>(DeleteRealmData.Params.TYPE_ID).get()
        val createdBefore: Long? =
            (params?.get(DeleteRealmData.Params.CREATED_BEFORE) as? TInteger)?.get()

        val provider = executionContext.schemaProvider

        // Reject rather than no-op (§4.1): a typo'd or non-realm-data id that quietly
        // deleted zero rows reads to the user as "the reset didn't work", and the
        // caller cannot tell that apart from "there was nothing to delete".
        if (typeId !in provider.realmDataTypeIds()) {
            throw TenshuException(
                NotFoundErrorDto(
                    "Not a realm data type: '$typeId'. Only types declaring the RealmData " +
                        "trait can be reset (SchemaProvider.realmDataTypeIds)."
                )
            )
        }

        // The createdAt binding is what `createdBefore` filters on — the one structural
        // field RealmData guarantees. Resolved against each ROW's own type below (a
        // sub-type inherits its parent's binding), so this is only the existence check
        // that makes an unfulfillable narrowing loud instead of silently deleting
        // everything.
        if (createdBefore != null &&
            provider.resolveValueRequirement(typeId, RealmData.CREATED_AT) == null
        ) {
            throw TenshuException(
                NotFoundErrorDto(
                    "Realm data type '$typeId' binds no RealmData.CREATED_AT property, so a " +
                        "'created before' narrowing cannot be evaluated for it."
                )
            )
        }

        var deleted = 0
        // The type→rows route: rows are only ever reachable through the service-data
        // relation array that holds them (there is no type-wide enumeration in the
        // store). The binding is a lookup KEY, handed back to the ordinary resolver so
        // the array reference is built exactly once, in one place.
        for (binding in provider.serviceDataArraysHolding(typeId)) {
            val arrayRef = executionContext
                .resolveServiceData(binding.service, binding.ref) as? ArrayDatabaseReference
                ?: continue

            // Materialised up front: the delete mutates the array being read.
            val rows = executionContext.getArrayData(arrayRef)?.toList() ?: continue

            val doomed = rows.mapNotNull { row ->
                val dataId = executionContext.getDataId(row) ?: return@mapNotNull null
                if (createdBefore != null &&
                    !isBeforeCutoff(executionContext, row, dataId, createdBefore)
                ) return@mapNotNull null
                Doomed(dataId = dataId, typeId = rowTypeId(row))
            }

            for (chunk in doomed.chunked(CHUNK)) {
                // Cancellation lands here, between chunks — never mid-chunk, so
                // what is written stays consistent (see the class KDoc).
                currentCoroutineContext().ensureActive()

                // ⚠ The chunk's three writes are `NonCancellable` **as a unit**:
                // once the array entries are dropped, the store rows they named
                // must follow, or a cancel would strand them — unreachable from
                // any array, already tombstoned, still occupying the store. The
                // interior `yield()`s still pace (a yield under NonCancellable
                // reschedules without throwing), so this costs latency, not
                // fairness: a cancel is honoured at the *next* chunk boundary.
                val chunkDeleted = withContext(NonCancellable) {
                    // Tombstones first: they are the durable record of the intent.
                    val emitted = tombstoneEmitter?.emitAll(
                        chunk.map {
                            TombstoneEmitter.Target(
                                entityId = it.dataId,
                                entityType = it.typeId,
                            )
                        }
                    )

                    // ⚠⚠ And the local delete is CONDITIONAL on them, which is the KDoc's own rule
                    // above ("deleting a row locally *before* its tombstone was durably queued loses
                    // the only handle on it"). `emitAll` answers short — today only all-or-nothing —
                    // when it could not name the targets for the wire, e.g. a `LockRealm` landing
                    // mid-resolve. Deleting anyway would destroy the ids that are the only way to
                    // tombstone those rows on a later run, and every peer would keep them for ever.
                    // ⚠ Skipping the chunk is safe and self-correcting: the rows are still here, so
                    // a re-run finds them again — the same "kill before the queue" case the KDoc
                    // already reasons about.
                    // ⚠⚠ Only a `null` blocks the delete: that means these rows *needed* tombstones
                    // and could not be named. An EMPTY list is the sync-off case — no realm, nothing
                    // to tell anyone — where deleting locally is correct and always has been, and
                    // skipping it would make a local-only user's reset silently do nothing.
                    if (tombstoneEmitter != null && emitted == null) return@withContext false

                    executionContext.database.removeAllFromArray(arrayRef, chunk.map { it.dataId })

                    // ⚠ Paced, not flat out. Each `deleteData` takes the one
                    // `DatabaseImpl` lock, and that lock is not fair: a background
                    // thread that re-acquires it immediately barges past the UI
                    // thread waiting for its own reactive reads. Off-main was not
                    // enough on a 10 k-row store — Control still missed the 5 s
                    // input deadline (§13.5). Yielding every [PACE_EVERY] deletes,
                    // plus a real gap between chunks, gives the waiter windows in
                    // which this coroutine holds nothing at all.
                    chunk.chunked(PACE_EVERY).forEach { slice ->
                        slice.forEach { executionContext.deleteData(it.dataId) }
                        yield()
                    }
                    true
                }
                // ⚠⚠ Only rows that were actually removed. A chunk skipped because its tombstones
                // could not be named is still *present*, and counting it would have the operation
                // report a complete reset over rows the user can still see — the after-the-fact
                // count is the only thing the UI has to go on.
                if (chunkDeleted) deleted += chunk.size

                // A whole-type reset is maintenance: it has no deadline, and
                // "responsive while it works" is worth more than "done sooner".
                // ~10 k rows costs about a second of added wall-clock here.
                delay(PACE_MS)
            }
        }

        return deleted.toTInteger(executionContext.context) as T
    }

    /**
     * Whether [row]'s `RealmData.CREATED_AT` value is strictly below [cutoff].
     *
     * ⚠ A row whose createdAt is **absent** is NOT deleted. The narrowing exists to
     * spare rows the user wants kept, so an unreadable timestamp must fall on the
     * conservative side — a bulk delete has no undo (§10 Q2).
     *
     * ⚠ The array holds bare composites; value properties live on the *entity*, so the
     * row is re-read `asEntity = true` to reach them. The binding is resolved against
     * the row's own type id, not the requested one, so a sub-type row resolves through
     * its parent's binding (`resolveValueRequirement` walks the parent chain).
     */
    private fun isBeforeCutoff(
        executionContext: ExecutionContext,
        row: Data,
        dataId: DataId,
        cutoff: Long,
    ): Boolean {
        val property = executionContext.schemaProvider
            .resolveValueRequirement(rowTypeId(row), RealmData.CREATED_AT)
            ?: return false
        val entity = executionContext.database.getData(dataId, asEntity = true) as? Entity<*>
            ?: return false
        val createdAt = (entity.getProperty<Data>(property.id) as? TInteger)?.get()
            ?: return false
        return createdAt < cutoff
    }

    private fun rowTypeId(row: Data): String =
        (row as? Entity<*>)?.type?.get() ?: row.id

    /**
     * One row selected for deletion, reduced to what the delete needs — so the
     * materialised row list can be dropped before the writes begin rather than
     * held across them.
     */
    private class Doomed(val dataId: DataId, val typeId: String)
}
