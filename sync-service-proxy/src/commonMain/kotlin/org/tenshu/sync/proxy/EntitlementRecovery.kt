package org.tenshu.sync.proxy

import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * Fetch the owner's `Entitlement` row **directly**, instead of waiting for the bulk replay to
 * reach it (`sync-device-catch-up.md` C1 / §5.3).
 *
 * ## The problem this exists for
 *
 * A device that is behind on history is behind on *everything*, including the one row that decides
 * whether it may write at all. A renewal sitting near the relay tip, far ahead of a wiped device's
 * cursor, leaves that device holding the expired row: its own window check presents no proof, the
 * relay refuses every write with `entitlement_required`, and the device stays write-dead for hours
 * until a bulk replay happens to reach one small row.
 *
 * Stated generally: **a row that gates the device's own ability to function must not queue behind
 * bulk history.** This is the narrow fix for that row; the general fix for the class is
 * newest-first catch-up (that note's §5.4).
 *
 * ## ⚠ Why the local row is the predicate, not the credential provider
 *
 * [recoverIfStale] fetches only when the **locally stored** row is missing or outside its window —
 * i.e. only when the relay could plausibly hold something better.
 *
 * Reading the store rather than asking `EntitlementRecordCredentialProvider` is deliberate. If the
 * local row is valid but the *provider* is empty, fetching changes nothing: the row is already here
 * and the fault is in the mirror, not the data. Predicating
 * on the durable copy means this never issues a request that could not possibly help.
 *
 * ## ⚠ No new operation, no relay change
 *
 * `fetchEntityBlocks` (`POST /sync/fetch-entity-blocks`) already exists and already bypasses the
 * cursor — the OnDemand byte path uses it (`docs/adr/0017-file-content-two-plane-channel.md`). Blocks are applied
 * through the ordinary [SyncProcessor.processSyncBlock] funnel, so every existing gate (tombstone
 * short-circuit, HLC compare, publisher-signature verification of an incoming `Entitlement`) runs
 * exactly as on the eager drain. ⚠⚠ That last one matters: **this path grants nothing it did not
 * verify** — a forged record fetched here is dropped by the same check that drops it on the drain.
 *
 * ## ⚠ Rate limiting is inherited, not invented
 *
 * The caller fires this only at an [RejectionBackoff] probing boundary, so the existing 2 s → 2 min
 * geometric window *is* the rate limit. A device that is genuinely unentitled therefore costs at
 * most one extra relay request per 2 minutes, and needs no timer of its own.
 */
internal class EntitlementRecovery(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val syncProcessor: SyncProcessor,
    private val relay: SyncRelay?,
    private val nowMs: () -> Long,
) {

    /** What one [recoverIfStale] attempt did — for the log line and for tests. */
    sealed interface Outcome {
        /** No relay wired (test/legacy wiring) or no owner yet. Nothing attempted. */
        data object NotAttempted : Outcome

        /** The local row is present and inside its window — fetching could not help. */
        data object AlreadyUsable : Outcome

        /** Fetched and applied [applied] blocks; [usable] is the local row's state afterwards. */
        data class Fetched(val applied: Int, val usable: Boolean) : Outcome

        /** The relay call failed. Transient; the next probing boundary retries. */
        data object Failed : Outcome
    }

    suspend fun recoverIfStale(): Outcome {
        val relay = relay ?: return Outcome.NotAttempted
        val owner = syncManager.currentOwnerId?.takeIf { it.isNotEmpty() } ?: return Outcome.NotAttempted

        if (localRecordUsable(owner)) return Outcome.AlreadyUsable

        // ⚠ Asked for by its wire id (B3 wire spec §11.3); with no realm key there is nothing to ask.
        val wireId = syncProcessor.wireIdOf(entitlementId(owner)) ?: return Outcome.NotAttempted
        val result = try {
            relay.fetchEntityBlocks(wireId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return Outcome.Failed
        }
        if (result !is TResult.Success) return Outcome.Failed

        // Ordinary ingest — see the class KDoc on why this must NOT be a privileged apply path.
        val blocks = result.value?.toList().orEmpty()
        blocks.forEach { syncProcessor.processSyncBlock(it) }

        return Outcome.Fetched(applied = blocks.size, usable = localRecordUsable(owner))
    }

    /**
     * Whether the stored `Entitlement` for [owner] exists and is inside its validity window right
     * now — the same window check `EntitlementRecordCredentialProvider.proofHeader` and the relay
     * both apply, so "usable" here means "would actually be presented and accepted".
     */
    private fun localRecordUsable(owner: String): Boolean {
        val row = executionContext.database.getData(entitlementId(owner)) as? Entitlement ?: return false
        val signed = EntitlementSignature.fieldsOf(row) ?: return false
        val now = nowMs()
        return now >= signed.validFrom && now <= signed.validUntil
    }

    private fun entitlementId(owner: String): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = Entitlement.ID,
            keyValue = owner,
        )
}
