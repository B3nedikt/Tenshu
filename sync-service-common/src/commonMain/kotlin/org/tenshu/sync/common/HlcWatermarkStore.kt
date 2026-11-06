package org.tenshu.sync.common

/**
 * The highest HLC a device's **store** has observed, as two plain values.
 *
 * Deliberately not a `HybridLogicalClock`: this crosses a persistence boundary and carries no
 * `deviceId`, because the deviceId is a property of the device publishing the watermark, not of
 * the watermark itself. Two runtimes on one device merge these and each stamps its own id.
 */
data class HlcWatermark(
    val physicalTime: Long,
    val logicalCounter: Long,
) : Comparable<HlcWatermark> {

    override fun compareTo(other: HlcWatermark): Int =
        if (physicalTime != other.physicalTime) physicalTime.compareTo(other.physicalTime)
        else logicalCounter.compareTo(other.logicalCounter)

    companion object {
        /** Never written / nothing observed. Distinguishable from a real tip, which is always > 0. */
        val NONE = HlcWatermark(0L, 0L)
    }
}

/**
 * A durable, monotonically-raised record of the highest HLC **this device's store** has observed
 * (`docs/proposals/sync-hlc-tip-device-identity.md` TC-2).
 *
 * ## What this exists to fix
 *
 * The signed HLC tip a device publishes is built from `HLCManager.current()`, which is *a
 * process's* clock — zero-initialised at startup, advancing only on a local write or an ingested
 * block. The relay keeps **one slot per `(ownerId, deviceId)`** and overwrites it unconditionally.
 * A device running more than one runtime under one deviceId — iOS route-1 (a runtime per
 * first-party app, one shared-Keychain deviceId), or two web tabs of one origin — therefore
 * publishes a *non-monotone* sequence into a single slot, which peers read as the T-T-04
 * forked-view tell. Diagnosed live 2026-08-28: a 16-minute backward tip from an iPhone pinned the
 * desktop tray's warning for ~50 minutes while sync was healthy.
 *
 * The same tip is the relay's tombstone-GC quorum input, so it has a second failure mode: a
 * restart publishes `(0,0)` (the tracker's empty-tip guard stops it *warning*, but the relay still
 * records it), and an idle client that neither pulls nor writes never advances its clock at all.
 * Making the watermark durable closes that independently of the multi-runtime problem, on every
 * platform.
 *
 * ## ⚠⚠ The invariant every implementation must preserve
 *
 * **The watermark describes the store it sits beside, never the process that wrote it.**
 *
 * `raiseTo` is honest only under that reading. When runtime B publishes a value raised by runtime
 * A, B asserts that *the device* observed that HLC — true exactly when A's ingest landed in a
 * store B shares. So an implementation must derive its location the way the **object store's** is
 * derived, and never from something with different sharing semantics.
 *
 * ⚠ Concretely, this is why `SecureStorageDelegate` is the wrong home on iOS despite being the
 * obvious one (it holds `LAST_SYNC_TIMESTAMP`): `IosSecureStorageDelegate` sets no
 * `kSecAttrAccessGroup`, so it is app-private, while `sharedReplicaDirectory()` resolves the
 * App-Group container *or falls back to app-local Documents*. Keying off the delegate would claim
 * sharing where there is none, and miss it where there is.
 *
 * Getting this wrong is not a cosmetic bug: an over-wide watermark makes a device claim it applied
 * tombstones it never saw, and the relay drops those bodies (`MailboxStore.acknowledgementSet`).
 */
interface HlcWatermarkStore {

    /**
     * The highest HLC this device's store has observed, or [HlcWatermark.NONE] if never written.
     */
    suspend fun read(): HlcWatermark

    /**
     * Raise the stored watermark to at least [candidate] and return the resulting value.
     *
     * ⚠ **Never lowers**, and must be atomic against a concurrent `raiseTo` from another runtime
     * over the same store — a lost update re-creates exactly the backward step this interface
     * exists to prevent.
     *
     * Returning the *merged* value is load-bearing: it is how a caller learns about a peer
     * runtime's progress in the same round-trip, so a lagging runtime adopts the device's real
     * position instead of republishing its own stale one.
     *
     * Implementations should skip the write entirely when [candidate] does not exceed what is
     * stored, so a quiet device does not write once per poll.
     */
    suspend fun raiseTo(candidate: HlcWatermark): HlcWatermark
}
