package org.tenshu.core.common.util

import org.tenshu.core.core.TContext
import org.tenshu.core.time.Clock
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.time.clock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

/**
 * Compares two HLC timestamps for ordering (Last Writer Wins).
 */
fun HybridLogicalClock.compare(other: HybridLogicalClock): Int =
    compareHlc(
        physicalTime.get(), logicalCounter.get(), deviceId.get(),
        other.physicalTime.get(), other.logicalCounter.get(), other.deviceId.get(),
    )

/**
 * An HLC reduced to its three plain values — the same instant, without the composite (and so
 * without a `TContext`). For stores that must persist and order HLCs but hold no schema
 * runtime: the sync relay records the HLC an admission was authored at in its device registry
 * and compares it against arriving `Device` tombstones.
 *
 * ⚠⚠ Ordering is [compareHlc], the *same* implementation [compare] uses — deliberately shared
 * rather than mirrored. Two replicas that order HLCs differently disagree about whether an
 * entity is alive, which is worse than either answer, and a copied comparison is exactly the
 * kind of thing that drifts silently.
 */
data class HlcStamp(
    val physicalTime: Long,
    val logicalCounter: Long,
    val deviceId: String,
) : Comparable<HlcStamp> {

    override fun compareTo(other: HlcStamp): Int =
        compareHlc(
            physicalTime, logicalCounter, deviceId,
            other.physicalTime, other.logicalCounter, other.deviceId,
        )

    /**
     * Canonical wire form, `physical:counter:deviceId` — carried on the admission routes so the
     * relay can record *when* an admission was authored. Device ids contain no `:`, and the
     * limit-3 split in [decodeHlcStamp] tolerates one that ever did.
     */
    fun encode(): String = "$physicalTime:$logicalCounter:$deviceId"
}

/** Canonical wire form of this HLC — [HlcStamp.encode] without the intermediate object. */
fun HybridLogicalClock.encodeAdmissionStamp(): String = toStamp().encode()

/** [HlcStamp] for this HLC. */
fun HybridLogicalClock.toStamp(): HlcStamp =
    HlcStamp(physicalTime.get(), logicalCounter.get(), deviceId.get())

/**
 * Parses [HlcStamp.encode]; `null` for absent or malformed input.
 *
 * ⚠ Malformed yields `null` rather than throwing. The stamp is an *ordering* hint, never an
 * authentication input: an unreadable one must degrade to "unknown", not reject the request
 * carrying it.
 */
fun decodeHlcStamp(raw: String?): HlcStamp? {
    val parts = raw?.split(':', limit = 3) ?: return null
    if (parts.size != 3) return null
    val physical = parts[0].toLongOrNull() ?: return null
    val counter = parts[1].toLongOrNull() ?: return null
    if (parts[2].isEmpty()) return null
    return HlcStamp(physical, counter, parts[2])
}

/**
 * The one HLC ordering: physical time, then logical counter, then device id — the tie-break
 * that makes the order total across devices.
 */
private fun compareHlc(
    physicalA: Long, counterA: Long, deviceA: String,
    physicalB: Long, counterB: Long, deviceB: String,
): Int {
    if (physicalA != physicalB) return physicalA.compareTo(physicalB)
    if (counterA != counterB) return counterA.compareTo(counterB)
    return deviceA.compareTo(deviceB)
}

/**
 * Utility class to manage the state of a local Hybrid Logical Clock.
 *
 * Thread-safe and lock-free: state lives in an [AtomicReference] holding an immutable
 * `(physicalTime, logicalCounter)` pair, updated via a CAS loop. Two callers racing
 * (e.g. `SyncObserver` on `Dispatchers.Default` and `SyncProcessor` on the sync ingest
 * path) each see a consistent transition without suspending or blocking.
 */
@OptIn(ExperimentalAtomicApi::class)
class HLCManager(
    val deviceId: String,
    private val context: TContext,
    private val clock: Clock = context.clock()
) {
    private data class HlcState(val physicalTime: Long, val logicalCounter: Long)

    private val state = AtomicReference(HlcState(0L, 0L))

    /**
     * Returns the current HLC state without advancing it. Used for HLC-tip piggyback
     * (`sync-backend-relay-plan.md` §5.1) where the device wants to publish "the highest
     * HLC I have observed so far" without producing a new local event.
     */
    fun current(): HybridLogicalClock {
        val s = state.load()
        return HybridLogicalClock.create(context, s.physicalTime, s.logicalCounter, deviceId)
    }

    /**
     * Raise the clock to at least (`physicalTime`, `logicalCounter`) without producing a local
     * event, and return the resulting state.
     *
     * ⚠⚠ **Only ever raises.** A clock that can be moved backwards is not a clock, and the whole
     * point of the caller (`HlcWatermarkStore`, `sync-hlc-tip-device-identity.md` TC-2) is to stop
     * a device publishing a value below one it already published.
     *
     * Two callers: startup seeding from the durable watermark — which is what stops a restarted
     * process publishing the `(0,0)` tip that silently zeroes the relay's tombstone-GC quorum for
     * this device — and adopting a peer runtime's progress after a `raiseTo` merge, so a lagging
     * runtime stops republishing its own stale position.
     *
     * Distinct from [update]: that folds in a *remote event's* HLC and floors the result at `now`,
     * because ingesting a block is itself a local event. This one is a pure raise — no `now`, no
     * counter bump — because reading a watermark observes nothing.
     */
    fun seed(physicalTime: Long, logicalCounter: Long): HybridLogicalClock {
        while (true) {
            val current = state.load()
            val higher = physicalTime > current.physicalTime ||
                (physicalTime == current.physicalTime && logicalCounter > current.logicalCounter)
            if (!higher) {
                return HybridLogicalClock.create(
                    context, current.physicalTime, current.logicalCounter, deviceId
                )
            }
            val next = HlcState(physicalTime, logicalCounter)
            if (state.compareAndSet(current, next)) {
                return HybridLogicalClock.create(
                    context, next.physicalTime, next.logicalCounter, deviceId
                )
            }
        }
    }

    /**
     * Generates a new HLC timestamp for a local event.
     */
    fun tick(): HybridLogicalClock {
        while (true) {
            val current = state.load()
            val now = clock.nowEpochMillis()
            val next = if (now > current.physicalTime) {
                HlcState(now, 0L)
            } else {
                HlcState(current.physicalTime, current.logicalCounter + 1)
            }
            if (state.compareAndSet(current, next)) {
                return HybridLogicalClock.create(
                    context, next.physicalTime, next.logicalCounter, deviceId
                )
            }
        }
    }

    /**
     * Updates the local clock state based on a received remote HLC timestamp and
     * returns the resulting local HLC. Atomic: callers do not need to call [tick]
     * separately afterwards.
     */
    fun update(remoteHlc: HybridLogicalClock): HybridLogicalClock {
        val remotePhysicalTime = remoteHlc.physicalTime.get()
        val remoteLogicalCounter = remoteHlc.logicalCounter.get()
        while (true) {
            val current = state.load()
            val now = clock.nowEpochMillis()
            val nextPhysicalTime =
                max(max(current.physicalTime, remotePhysicalTime), now)
            val nextLogicalCounter = when {
                nextPhysicalTime == current.physicalTime &&
                        nextPhysicalTime == remotePhysicalTime ->
                    max(current.logicalCounter, remoteLogicalCounter) + 1

                nextPhysicalTime == current.physicalTime -> current.logicalCounter + 1
                nextPhysicalTime == remotePhysicalTime -> remoteLogicalCounter + 1
                else -> 0L
            }
            val next = HlcState(nextPhysicalTime, nextLogicalCounter)
            if (state.compareAndSet(current, next)) {
                return HybridLogicalClock.create(
                    context, next.physicalTime, next.logicalCounter, deviceId
                )
            }
        }
    }
}
