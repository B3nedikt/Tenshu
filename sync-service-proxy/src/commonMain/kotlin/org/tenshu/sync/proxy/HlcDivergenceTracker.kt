package org.tenshu.sync.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.common.util.compare
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.hlcTipCanonicalMessage

/**
 * Client-side observer for peer HLC tips returned by `POST /sync/changes` (per
 * `docs/proposals/sync-backend-relay-plan.md` §5.1 + `docs/sync/threat-model.md`
 * §11.3). Verifies each tip's signature against the peer device's public key and
 * tracks the highest HLC observed per peer; emits a [DivergenceWarning] when:
 *
 *  - **SignatureMismatch** — the relay returned a tip whose signature doesn't verify
 *    against the peer's known public key. Either the relay forged it (the I6 attack
 *    this whole mechanism is designed to detect) or the local Device-entity view of
 *    the peer's public key is stale (less likely after pairing has fully propagated).
 *  - **Regression** — the relay returned a tip whose HLC is *lower* than the highest
 *    HLC we've previously seen from that peer. Either the peer rolled back (shouldn't
 *    happen — HLCs are monotonic) or the relay served a stale tip to mask later
 *    blocks from us (a "forked view" attack per T-T-04).
 *
 * An all-zero HLC (physical time `0`) is the relay's "no tip yet" sentinel and is
 * skipped as transient (never a warning) — see the empty-tip guard in [observe].
 *
 * **Not** detected here:
 *  - "Two of my polls saw inconsistent peer tips" — that's a stronger detection
 *    requiring N-of-N comparison over time. The simpler regression check catches the
 *    common case (relay rewinds) without the bookkeeping.
 *  - Cross-device divergence — would require comparing my tracker against another
 *    realm device's tracker over a side channel. Out of scope.
 *
 * A warning **auto-clears on recovery**: once the offending peer advances its HLC past
 * the floor it regressed from (or its signature verifies again), the warning resets to
 * `null`. Without this it was a one-way latch — [clearWarning] is never called in
 * production, so a single stale/reset relay tip pinned the tray's DIVERGENCE triangle
 * for the whole process lifetime even after sync had fully recovered.
 *
 * State today is **in-memory only**. Across an app restart, the tracker forgets the
 * highest HLC seen per peer and won't false-positive (it just starts from scratch and
 * accepts the next tip as the new floor). A future iteration may persist via
 * [org.tenshu.core.common.delegate.SecureStorageDelegate] if regression detection
 * needs to survive restarts to catch slow-rolling forked-view attacks.
 *
 * Public-key lookup is delegated to [publicKeyLookup] — production wiring resolves
 * the peer's [Device][org.tenshu.identity.Device] entity from the local synced DB
 * and returns its `publicKey`. Returning `null` means "I don't know this device yet";
 * the tracker treats it as a transient state and skips signature verification (no
 * warning emitted) rather than crying wolf during the propagation window.
 */
class HlcDivergenceTracker(
    private val cryptoManager: SyncCryptoManager,
    private val publicKeyLookup: (deviceId: String) -> ByteArray?,
    /**
     * Wall clock for [DivergenceWarning.observedAtMillis]. Deliberately **required**: a default
     * would stamp every warning from an un-updated wiring at epoch 0, which a recency-bounded
     * surface reads as "ancient" and silently declines to show. A missing clock should be a
     * compile error, not an invisible loss of the warning.
     */
    private val nowMs: () -> Long,
) {

    private val highestSeenByDevice = mutableMapOf<String, HybridLogicalClock>()

    private val _warning = MutableStateFlow<DivergenceWarning?>(null)
    val warning: StateFlow<DivergenceWarning?> = _warning

    /**
     * Record observation of [tip] from a peer. Verifies signature + checks for
     * regression; updates [warning] if either fails. Idempotent for a given tip.
     */
    fun observe(tip: SignedHlcTip) {
        val deviceId = tip.deviceId.get()

        if (tip.hlc.physicalTime.get() <= 0L) {
            // An all-zero HLC (physical time 0) is the relay's "no tip yet" sentinel for a
            // device it has an entry for but from which it hasn't relayed a real signed tip
            // yet (freshly (re)registered peer, or a relay whose tip state was reset). It
            // carries no meaningful ordering — always lower than a real tip we've already
            // seen — so treating it as a regression is a false positive (it would fire the
            // "Sync issue detected" tray warning with previous≈now / current=0). Register
            // the peer as *seen* (so peer-device tracking works for a fresh device whose
            // clock hasn't advanced) but never let the sentinel set or *lower* the floor and
            // never warn: getOrPut only seeds a floor when we've seen nothing from this peer
            // yet — if a real tip already set it, the sentinel is ignored (closing the
            // false positive); if not, seeding at 0 is harmless, the next real tip advances it.
            highestSeenByDevice.getOrPut(deviceId) { tip.hlc }
            return
        }

        // Signature verification — the load-bearing check that makes the relay non-
        // forgery-capable. We skip when we don't yet know the peer's key (transient
        // state during propagation); see KDoc.
        val publicKey = publicKeyLookup(deviceId)
        if (publicKey != null) {
            val expected = hlcTipCanonicalMessage(
                ownerId = tip.ownerId.get(),
                deviceId = deviceId,
                hlc = tip.hlc
            )
            if (!cryptoManager.verifyEd25519(publicKey, expected, tip.signature.get())) {
                raise(DivergenceWarning.SignatureMismatch(deviceId, nowMs()))
                return
            }
        }

        // Regression check.
        val previous = highestSeenByDevice[deviceId]
        if (previous != null && tip.hlc.compare(previous) < 0) {
            raise(
                DivergenceWarning.Regression(
                    peerDeviceId = deviceId,
                    previousHlcPhysicalTime = previous.physicalTime.get(),
                    currentHlcPhysicalTime = tip.hlc.physicalTime.get(),
                    observedAtMillis = nowMs(),
                )
            )
            return
        }

        // Healthy observation: advance the floor. (Equal HLC is fine — same poll
        // re-served, or two HLCs that compare-equal modulo deviceId tiebreak.)
        if (previous == null || tip.hlc.compare(previous) > 0) {
            highestSeenByDevice[deviceId] = tip.hlc

            // Recovery auto-clear: this peer has now advanced *past* the floor it last
            // regressed from (or its signature verifies again), so the transient — or
            // since-resolved — divergence is over. Without this the warning was a one-way
            // latch: `clearWarning()` is never called in production, so a single stale/
            // reset relay tip pinned the tray's DIVERGENCE triangle for the whole process
            // lifetime even after sync fully recovered. Scoped to *this* peer's warning so
            // one healthy peer can't mask another peer's still-live divergence. The
            // regression path deliberately does NOT lower the floor, so clearing only
            // happens on genuine forward progress beyond the pre-regression high-water mark.
            if (_warning.value?.peerDeviceId == deviceId) {
                _warning.value = null
            }
        }
    }

    /**
     * Publishes [warning] unless the standing one already reports the same evidence.
     *
     * ⚠⚠ A repeat observation of the *same* regression is **not new evidence**, and treating it as
     * such is what made the tray warning unfalsifiable. The offending tip sits in the relay's
     * single `(ownerId, deviceId)` slot, so every poll re-reads it and re-derives an identical
     * `Regression`; restamping on each would leave [DivergenceWarning.observedAtMillis] pinned to
     * "now" forever, and any surface that ages a warning out could never do so. Keeping the first
     * stamp makes the field mean *first seen*, which is the only reading a recency bound can use.
     *
     * Detection is unchanged: the warning is still raised, still latched, and still cleared only
     * by the recovery path in [observe]. Only the stamp is held.
     */
    private fun raise(warning: DivergenceWarning) {
        val current = _warning.value
        if (current != null && current.sameEvidenceAs(warning)) return
        _warning.value = warning
    }

    /** Test/UI helper — clears the current warning (e.g. after the user dismisses). */
    fun clearWarning() {
        _warning.value = null
    }

    /** Test helper — returns the set of peer deviceIds the tracker has observed. */
    fun seenPeerDeviceIds(): Set<String> = highestSeenByDevice.keys.toSet()
}

/**
 * Surfaces a divergence problem detected by [HlcDivergenceTracker]. Rendered today by the
 * desktop tray via `ControlBridge.getSyncState`; the variants distinguish
 * "the relay is lying or stale" (Regression) from "something cryptographically wrong"
 * (SignatureMismatch).
 */
sealed class DivergenceWarning {

    /** The peer device this warning is about — used to scope the recovery auto-clear. */
    abstract val peerDeviceId: String

    /**
     * Wall-clock ms when this warning was **first** raised — not when it was last re-observed.
     * See [HlcDivergenceTracker.raise] for why the distinction is the whole point of the field:
     * a surface can only age a warning out if the stamp stops moving.
     */
    abstract val observedAtMillis: Long

    /**
     * Whether [other] reports the same evidence as this warning, ignoring [observedAtMillis].
     * Spelled out per variant rather than derived from `equals`, because the timestamp is a
     * constructor property and so *is* part of the generated equality.
     */
    abstract fun sameEvidenceAs(other: DivergenceWarning): Boolean

    /** Peer tip's signature didn't verify against the peer's known public key. */
    data class SignatureMismatch(
        override val peerDeviceId: String,
        override val observedAtMillis: Long,
    ) : DivergenceWarning() {

        override fun sameEvidenceAs(other: DivergenceWarning): Boolean =
            other is SignatureMismatch && other.peerDeviceId == peerDeviceId
    }

    /** Peer tip's HLC went *backward* — relay served a stale tip or peer rolled back. */
    data class Regression(
        override val peerDeviceId: String,
        val previousHlcPhysicalTime: Long,
        val currentHlcPhysicalTime: Long,
        override val observedAtMillis: Long,
    ) : DivergenceWarning() {

        override fun sameEvidenceAs(other: DivergenceWarning): Boolean =
            other is Regression &&
                other.peerDeviceId == peerDeviceId &&
                other.previousHlcPhysicalTime == previousHlcPhysicalTime &&
                other.currentHlcPhysicalTime == currentHlcPhysicalTime
    }
}
