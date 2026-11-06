package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.DeviceLastSeen

/**
 * Records, per realm device, the newest write this replica has ingested from it — the data behind
 * the roster's "last seen" line ([DeviceLastSeen]).
 *
 * **Why it lives at the HLC seam.** Every ingested block already advances the local clock with
 * `hlcManager().update(block.hlc)`, and that HLC carries its **author's** `deviceId` and physical
 * time. So the fact is free: it is read off a value the ingest path is holding anyway, with no new
 * wire field, no heartbeat, and no request to the relay. Deriving it any other way — a synced
 * liveness row, a periodic ping — would put traffic on the wire for something every device can
 * observe locally, which is the shape behind the 99.8 %-of-realm-traffic broadcast loop.
 *
 * ⚠ **Deliberately stamped before the block is known to be usable.** A block that fails to decrypt
 * or is dropped by the schema fence still proves the device wrote something and the relay delivered
 * it — which is exactly the question "last seen" answers. Gating on successful application would
 * make a *stale* peer look dead, and a stale peer is precisely the one an operator needs to see.
 *
 * ⚠ Writes are throttled ([GRANULARITY_MS]) against an in-memory high-water mark, so the common
 * case — a burst of blocks from one device — costs a map lookup and no store access at all. The
 * store on this project is the ANR/OOM-sensitive component; a row per ingested block would be a
 * write amplification of exactly the kind that has bitten before.
 */
internal class DeviceLastSeenRecorder(
    private val executionContext: ExecutionContext,
    private val ownDeviceId: () -> String,
) {

    /** Highest `lastSeenMs` written per device this process — the throttle's memory. */
    private val written = mutableMapOf<String, Long>()

    /**
     * Latched after a failure so a broken recorder costs one log line, not one per block.
     *
     * ⚠⚠ **Ingest must not acquire a failure mode for a roster nicety.** The obvious one is real:
     * this writes a type registered by `identityServiceCommonModule`, and a wiring that has the
     * sync service without the identity service — several test harnesses today, and any future
     * slimmed-down graph — throws `No composite factory registered` from inside `saveData`. Before
     * this guard that exception propagated out of `decryptAndParse` and killed the ingest of a
     * perfectly good block. A device that cannot say when it last saw its peers is a cosmetic
     * loss; a device that cannot ingest is not.
     */
    private var degraded = false

    fun record(hlc: HybridLogicalClock) {
        if (degraded) return
        runCatching { recordOrThrow(hlc) }.onFailure { failure ->
            degraded = true
            println(
                "WARN DeviceLastSeenRecorder: disabled for this session — the roster's last-seen " +
                    "line will be absent (${failure::class.simpleName}: ${failure.message})"
            )
        }
    }

    private fun recordOrThrow(hlc: HybridLogicalClock) {
        val deviceId = hlc.deviceId.get()
        if (deviceId.isEmpty()) return
        // Our own writes are not an observation of anything, and the roster marks this device
        // separately. Recording them would also make every local save touch the store twice.
        if (deviceId == ownDeviceId()) return

        val seenMs = hlc.physicalTime.get()
        val lastWritten = written[deviceId]
        if (lastWritten != null && seenMs - lastWritten < GRANULARITY_MS) return

        val rowId = DataId.StringDataId.create(
            context = executionContext.context,
            type = DeviceLastSeen.ID,
            keyValue = deviceId,
        )
        // ⚠ Never move the value backwards: blocks arrive out of order (per-lane cursors drift
        // arbitrarily, `sync-lane-cursor-split.md` L1b), so an older block reaching us later must
        // not make a live device look staler than it is.
        val recorded = (executionContext.database.getData(rowId) as? DeviceLastSeen)?.lastSeenMs?.get()
        if (recorded != null && recorded >= seenMs) {
            written[deviceId] = recorded
            return
        }

        executionContext.saveData(
            DeviceLastSeen.create(
                context = executionContext.context,
                deviceId = deviceId,
                lastSeenMs = seenMs,
            ),
            // ⚠ `DERIVED`, and not merely because the type carries no `Syncable` trait: this is
            // exactly the shape the origin exists for — every device computes its own answer from
            // its own ingest window, so broadcasting one would be a disagreement, not a duplicate.
            // Belt and braces against a later reader "helpfully" making the type Syncable.
            origin = WriteOrigin.DERIVED,
        )
        written[deviceId] = seenMs
    }

    private companion object {
        /**
         * How much the observation must advance before it is worth a store write. Five minutes is
         * far below any granularity the UI renders ("2 hours ago", "3 days ago") and far above the
         * rate at which a sync burst produces blocks.
         */
        const val GRANULARITY_MS = 5 * 60 * 1000L
    }
}
