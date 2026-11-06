package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.db.StoreWipeNotFinished
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.pendingTombstoneCount
import org.tenshu.sync.proxy.purgeOutboundQueues
import org.tenshu.sync.RevokeDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * `ForgetThisDevice` — discard this device's realm identity (see the op's KDoc for why).
 *
 * ⚠⚠ **The step order is the whole implementation.** Each edge is pinned by a failure that has
 * actually happened on this project:
 *
 * 1. **Retire the row, and wait for the tombstone to leave.** `RevokeDevice` has no self-guard by
 *    design, so a device can retire itself while it still holds the keys to prove it. The push is
 *    signed with the A4 key step 3 deletes, so a tombstone still sitting in `PENDING_TOMBSTONES`
 *    when the key goes can **never** be pushed — and the wipe manufactures the orphaned `Device`
 *    row it exists to prevent.
 * 2. **Purge the outbound queues — between 1 and 3, not after.** Minting an `ExecutionContext` is
 *    opening a session; doing it once the custody keys are gone silently yields nothing (observed
 *    on iOS 2026-08-19, where the purge reported "not reachable" and never ran).
 * 3. **Purge the custody bucket.**
 *
 * ⚠ A failed retirement never blocks the wipe. People forget a device precisely when something is
 * broken, and a refusal would strand them; the outcome string names the leftover row and the remedy
 * instead of hiding it.
 */
internal class ForgetThisDeviceImplementation(
    private val syncManager: SyncManager,
    /**
     * Builds the real [RevokeDeviceImplementation], not a re-implementation of it. The retire step
     * must stay byte-identical to an ordinary removal — same tombstone, same `revokedDeviceId`,
     * same subtree revocations — or a device that forgets itself would leave the realm in a subtly
     * different state from one removed by a peer.
     *
     * ⚠ A **factory** rather than an instance so each run can bind its own strip-failure observer;
     * the instance it returns is still the ordinary one.
     */
    private val revokeDevice: (onRelayStripFailed: () -> Unit) -> AsyncOperationImplementation,
    private val nowMs: () -> Long,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        // 1 — leave the realm before destroying the means to say so.
        val retirement = runCatchingCancellable { retireThisDeviceRow(executionContext, implementationFactory) }
            .getOrElse { LEFTOVER_ROW }

        // 2 — drop what this device staged to send; it is addressed to a realm it is leaving.
        // ⚠ The realm being left, so the owner-scoped rekey retry journal is cleared too.
        val queued = runCatching {
            executionContext.purgeOutboundQueues(syncManager.residentOwnerId)
        }.getOrNull()

        // 3 — the local copy of realm data (RG-3 / D3, decided 2026-08-23).
        //
        // ⚠⚠ BEFORE the custody purge, and the order is the whole point. Custody-gone-store-present
        // is the exact half-state D3 forbids: a fresh device id registered with no realm, `401` on
        // every push, and an app that still looks signed in to the realm it just left. The reverse —
        // store gone, custody briefly alive — is recoverable, because a device that still holds its
        // identity simply re-syncs. So if this sequence is interrupted, fail toward the recoverable
        // half.
        //
        // ⚠⚠ A wipe that STARTED and did not finish (`StoreWipeNotFinished`, the iOS shared store on
        // SQLite) is a third outcome, never folded into "not dropped" (`ios-shared-store-sqlite.md`
        // §13's rule): the store is still present behind its wipe marker, so step 4 does not run and
        // custody stays. The user repeats the forget, which completes over the finished or
        // still-marked store. A plain try, not `runCatching(...).getOrDefault`, so the type test is
        // on the caught exception itself.
        var notFinished: StoreWipeNotFinished? = null
        val storeDropped = try {
            syncManager.wipeLocalStore()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unfinished: StoreWipeNotFinished) {
            notFinished = unfinished
            false
        } catch (failure: Throwable) {
            false
        }
        val queueNote = queued?.let { "$it queued item(s) dropped" } ?: "queues not reachable"
        if (notFinished != null) {
            println("forgetThisDevice -> the store's wipe did not finish (${notFinished.message}); custody kept")
            return TStringDto("⚠ not finished — $retirement; $queueNote; the local store's wipe did not finish, try again") as T
        }

        // 4 — the identity itself (its cursor reset tolerates a fenced handle, B.14 (192)).
        syncManager.forgetThisDevice()

        // ⚠ Reported, never assumed. `WipeableObjectStore` is an optional capability, so a store that
        // cannot wipe says so here rather than leaving a silent half reset behind.
        val storeNote = if (storeDropped) "local store dropped" else "⚠ local store NOT dropped"
        return TStringDto("forgotten — $retirement; $queueNote; $storeNote") as T
    }

    private suspend fun retireThisDeviceRow(
        executionContext: ExecutionContext,
        factory: OperationImplementationFactory,
    ): String {
        val deviceId = syncManager.currentRealmContext()?.deviceId ?: return "no realm to leave"

        // ⚠ Set from inside the revocation when the relay refuses the admission strip — see the
        // short-circuit below for what it is used for, and what it deliberately is not.
        var relayStripFailed = false
        val revoked = runCatchingCancellable {
            revokeDevice { relayStripFailed = true }.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = factory,
                caller = null,
                params = mapOf(
                    RevokeDevice.Params.DEVICE_ID to TStringDto(deviceId),
                ),
            )
        }
        if (revoked.isFailure) return LEFTOVER_ROW

        // ⚠⚠ **Do not wait out the timeout when the relay just refused us.** The strip and the
        // tombstone push are different endpoints on the *same* relay, and the things that break one
        // — the realm gone, no network, broken auth — break the other. Waiting the full
        // [RETIREMENT_PUSH_TIMEOUT_MS] there buys nothing and costs every second of it, in front of
        // a user who has already committed to an irreversible action and is watching a spinner.
        //
        // Measured 2026-08-26: a relay answering `404 Realm not found` made this the *common* path,
        // not an edge — the poll ran its full 8s on every attempt, which is most of the ~10s wait
        // that got reported as "confirming does nothing".
        //
        // ⚠⚠ It reports [UNCONFIRMED], never [LEFTOVER_ROW]. This is a **pessimistic short-circuit,
        // not a diagnosis**: a strip failure makes a successful push unlikely, not impossible, so
        // claiming the row is definitely still there would send the user off to fix something that
        // may not be broken. Say what is true — we stopped waiting and do not know.
        //
        // ⚠⚠ And it deliberately does NOT look at *why* the strip failed. The relay reports it as a
        // message string with no structured status, and matching relay errors by their text is a
        // trap this project has already been caught by (`docs/tech-debt.md`: an H2 connection makes
        // the reason phrase empty, so the match is inert in production and green everywhere else).
        // Any failure is treated the same, which needs no parsing to stay correct.
        if (relayStripFailed) return UNCONFIRMED

        // ⚠⚠ "Actually left" is directly observable: `SyncScheduler` drains PENDING_TOMBSTONES and
        // removes what it pushed, so an empty array is the honest signal. Bounded — a device with no
        // connectivity must still be able to forget itself.
        val deadline = nowMs() + RETIREMENT_PUSH_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (executionContext.pendingTombstoneCount() == 0) return "removed from your realm"
            delay(RETIREMENT_POLL_MS)
        }
        return LEFTOVER_ROW
    }

    private companion object {
        /** Long enough for a wake + one relay round trip; short enough that an offline device is
         *  not held hostage, which is the state a user forgetting a device is most likely in. */
        const val RETIREMENT_PUSH_TIMEOUT_MS = 8_000L
        const val RETIREMENT_POLL_MS = 150L

        /** ⚠ An *announced* outcome with a named remedy — never a silent orphan. */
        const val LEFTOVER_ROW =
            "⚠ this device is still listed in your realm — remove it from another device"

        /**
         * ⚠ Distinct from [LEFTOVER_ROW] on purpose. We stopped waiting early because the relay
         * refused us, so we never learned whether the tombstone landed — asserting the row is still
         * there would send the user to fix something that may already be fine.
         */
        const val UNCONFIRMED =
            "⚠ could not confirm this device left your realm — check your devices from another one"
    }
}
