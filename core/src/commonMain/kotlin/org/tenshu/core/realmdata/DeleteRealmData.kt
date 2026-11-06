package org.tenshu.core.realmdata

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Deletes every row of the [RealmData][org.tenshu.core.trait.RealmData] type
 * [typeId], realm-wide, optionally narrowed to rows created before
 * [createdBefore] (epoch millis). Returns how many rows were deleted.
 *
 * `docs/proposals/control-data-reset.md` R1 — one **generic** op rather than N
 * per-service ones (§4, D2/option B): it enumerates the schema, so it cannot drift
 * out of sync with it, and it needs no per-service work to cover a new data type.
 *
 * ### ⚠⚠ It is realm-wide, and it converges
 *
 * There is deliberately **no per-device option** (§6, D3): a device-local delete would
 * be undone by the next pull, so offering the choice would be offering a button that
 * silently does nothing. The implementation emits **one tombstone per deleted
 * top-level entity** — a naive `deleteData` loop deletes locally and converges
 * nowhere, because `SyncObserver.onDataDeleted` is deliberately a no-op (§3).
 *
 * ⚠ What "deleted" honestly means (§5): the synced row is tombstoned and disappears
 * from every device — that part is real. Content-plane blobs the row referenced become
 * unreferenced (relay reclaim is account-lifecycle-only, HR#1 makes per-file reclaim
 * impossible), and the ciphertext blocks that carried the original writes stay in relay
 * history until compaction advances the floor. Call-site copy says *"removes it from
 * all your devices"*, never *"erases it everywhere"*.
 *
 * ### The filter, and why `createdBefore` is the whole of v1 (§4.1)
 *
 * A general predicate would need a serialisable filter over arbitrary fields — a
 * chain-DSL-sized problem. But `RealmData` already guarantees exactly one structural
 * field, [RealmData.CREATED_AT][org.tenshu.core.trait.RealmData.Companion.CREATED_AT],
 * bound per type via `fulfilValueRequirement`. That single contract covers the real
 * case ("delete the workouts the broken importer wrote, before I re-imported") and is
 * generic by construction, because every realm data type fulfils it. Anything richer
 * waits for a demonstrated second case (Q1).
 *
 * ⚠ A type that is not realm data is **rejected**, not silently ignored — a typo'd id
 * that quietly deleted nothing would read to the user as "the reset didn't work".
 */
suspend fun RealmDataService.deleteRealmData(
    typeId: String,
    createdBefore: Long? = null,
): TResult<TInteger> =
    findOperation<DeleteRealmData>(DeleteRealmData.ID)
        .deleteRealmData(typeId, createdBefore)
        .asTaskInteractor<TInteger>()
        .execute()

interface DeleteRealmData : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun deleteRealmData(
        typeId: String,
        createdBefore: Long?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/realmData/service/deleteRealmData"

        val Schema = operation(
            id = ID,
            receiver = RealmDataService.Schema.toType(),
            params = {
                string(id = Params.TYPE_ID)
                // `int`, not `timestamp`: the wire-proven optional-numeric shape
                // (`UploadFile.Params.SOURCE_MODIFIED_AT`), and the value is compared
                // against a `TInteger`-backed cell anyway — `UnixInstant` is a
                // *marker* over the same encoding, so the narrower declaration would
                // buy nothing and fork the encode path in the Ro.
                int(id = Params.CREATED_BEFORE, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
            // The deleted-row count. Q4: "Delete all 3,002" reads very differently
            // from "Delete all", and the Data section has no cheap count of its own
            // (core-realm-data.md Phase 6 is unbuilt) — so the op reports what it
            // actually did rather than the UI guessing beforehand.
            returnType = TInteger.Schema.toType(),
        )
    }

    object Params {
        const val TYPE_ID = "$ID/typeId"

        /**
         * Exclusive upper bound on the row's `RealmData.CREATED_AT` value, epoch
         * millis. Absent ⇒ every row of the type.
         */
        const val CREATED_BEFORE = "$ID/createdBefore"
    }
}
