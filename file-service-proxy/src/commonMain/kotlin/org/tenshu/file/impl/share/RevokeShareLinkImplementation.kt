package org.tenshu.file.impl.share

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TBoolean
import org.tenshu.core.TenshuException
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.file.share.Share
import org.tenshu.core.number.TInteger
import org.tenshu.sync.common.ShareRelayClient

/**
 * Mode-A `RevokeShareLink` op (`docs/file-sharing.md`).
 *
 * **End-to-end (P1b):**
 *  1. Resolves the local [Share] entity by `shareId`.
 *  2. Flips `revoked = true` and re-saves — the entity is `Syncable(Eager)`,
 *     so the revocation replicates to every other realm device on the next
 *     drain. Each peer's *My shares* list filters revoked rows out. This is
 *     the **local source of truth**: the UI hides the share immediately,
 *     before any relay round-trip.
 *  3. `DELETE /share/{shareId}` on the relay (JWT-scoped to `ownerId`) so the
 *     recipient-side 410 fires. The relay call is best-effort: the local flip
 *     already succeeded, so a transient relay failure does not fail the op
 *     (the chunks age out via the relay's revoked/orphan GC regardless, and a
 *     later re-revoke re-issues the DELETE). [shareRelayClient] is `null` in
 *     local-only mode (test harnesses / pre-sync proxies) — step 3 is skipped.
 */
internal class RevokeShareLinkImplementation(
    private val shareRelayClient: ShareRelayClient? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val shareIdParam: TString = params.read(RevokeShareLink.Params.SHARE_ID)
        val shareId = shareIdParam.get()

        val shareDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Share.ID,
            keyValue = shareId,
        )
        val existing = executionContext.database.getData(shareDataId)
        if (existing == null) {
            throw TenshuException(
                NotFoundError.create(
                    context = executionContext.context,
                    message = "Share '$shareId' not found"
                )
            )
        }

        // The DB returns either a typed `Entity<Share>` (saveData-roundtrip)
        // or a `Composite` (DTO roundtrip). Both expose the same field accessors
        // via `getField`/`getFieldValue`; we read through whichever shape
        // surfaced and rebuild a Share with `revoked = true`.
        val revoked = Entity.create<Share>(
            context = executionContext.context,
            composite = Share.create(
                context = executionContext.context,
                shareId = existing.readString(Share.Field.SHARE_ID),
                fileId = existing.readString(Share.Field.FILE_ID),
                createdAtMillis = existing.readLong(Share.Field.CREATED_AT_MILLIS),
                createdOnDeviceId = existing.readString(Share.Field.CREATED_ON_DEVICE_ID),
                expiresAtMillis = existing.readLong(Share.Field.EXPIRES_AT_MILLIS),
                maxOpens = existing.readLong(Share.Field.MAX_OPENS),
                revoked = true,
            )
        )
        executionContext.saveData(revoked)

        // Propagate to the relay so the recipient-side 410 fires. Best-effort:
        // the local Eager flip is the source of truth, so a relay failure here
        // is swallowed (the revoked record/chunks age out via the relay GC and
        // a later re-revoke re-issues the DELETE).
        shareRelayClient?.deleteShare(shareId)

        return null as T
    }

    private fun Data.readString(fieldId: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(fieldId).get()
        is Composite -> getField<TString>(fieldId).get()
        else -> error("Unexpected Share shape for field $fieldId: $this")
    }

    private fun Data.readLong(fieldId: String): Long = when (this) {
        is Entity<*> -> getFieldValue<TInteger>(fieldId).get()
        is Composite -> getField<TInteger>(fieldId).get()
        else -> error("Unexpected Share shape for field $fieldId: $this")
    }

    @Suppress("unused")
    private fun Data.readBoolean(fieldId: String): Boolean = when (this) {
        is Entity<*> -> getFieldValue<TBoolean>(fieldId).get()
        is Composite -> getField<TBoolean>(fieldId).get()
        else -> error("Unexpected Share shape for field $fieldId: $this")
    }
}
