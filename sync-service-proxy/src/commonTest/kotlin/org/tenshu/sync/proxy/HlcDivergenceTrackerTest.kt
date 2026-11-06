package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests the receiver-side detection logic per `sync-backend-relay-plan.md` §5.1
 * + threat-model T-T-04. Pins:
 *
 *  - Healthy monotonic advance does NOT raise a warning.
 *  - A regression (peer tip's HLC < tracked highest) → `Regression` warning.
 *  - A signature that doesn't verify against the peer's key → `SignatureMismatch`.
 *  - Unknown peer (publicKeyLookup returns null) is treated as transient — no warning.
 */
class HlcDivergenceTrackerTest {

    private lateinit var testProxy: TestProxy
    // The tracker's logic is independent of the crypto backend; using
    // BootstrapSyncCryptoManager keeps the `pub == priv` stub invariant the
    // test fixtures rely on (see `peerPublicKey = peerPrivateKey` below).
    // Real Ed25519 lives in `DefaultSyncCryptoManager` post-Phase-4 slice A
    // and can't satisfy the deterministic-stub setup these unit tests use.
    private val crypto = BootstrapSyncCryptoManager()

    private val ownerId = "alice"
    private val peerId = "peer-device"
    private val peerPrivateKey = "peer-key".encodeToByteArray()
    private val peerPublicKey = peerPrivateKey  // stub: pub == priv

    /**
     * Hand-advanced clock. The observation stamp is part of the tracker's contract now
     * ("first seen", never "last seen"), and a real clock cannot tell those two apart.
     */
    private var clockMs = 1_000L

    private fun newTracker(publicKeyLookup: (String) -> ByteArray?) =
        HlcDivergenceTracker(crypto, publicKeyLookup) { clockMs }

    @BeforeTest
    fun setup() {
        clockMs = 1_000L
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
    }

    private fun signedTip(
        physicalTime: Long,
        signingKey: ByteArray = peerPrivateKey
    ): SignedHlcTip {
        val hlc = HybridLogicalClock.create(testProxy.tContext, physicalTime, 0, peerId)
        val signature = stubEd25519Signature(
            signingKey,
            hlcTipCanonicalMessage(ownerId, peerId, hlc)
        )
        return SignedHlcTip.create(testProxy.tContext, ownerId, peerId, hlc, signature)
    }

    @Test
    fun monotonicAdvance_doesNotWarn() {
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 100L))
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 300L))
        assertNull(tracker.warning.value)
    }

    @Test
    fun equalHlc_doesNotWarn_andDoesNotRegress() {
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 100L))
        // Same poll re-served — relay can legitimately return the same tip again.
        tracker.observe(signedTip(physicalTime = 100L))
        assertNull(tracker.warning.value)
    }

    @Test
    fun regression_emitsWarning() {
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))  // backwards!

        val warning = assertNotNull(tracker.warning.value)
        assertTrue(warning is DivergenceWarning.Regression)
        assertEquals(peerId, warning.peerDeviceId)
        assertEquals(200L, warning.previousHlcPhysicalTime)
        assertEquals(100L, warning.currentHlcPhysicalTime)
    }

    @Test
    fun signatureMismatch_emitsWarning() {
        val tracker = newTracker { peerPublicKey }
        // Tip signed with a different key than the peer's known public key — what would
        // happen if a hostile relay forged a peer tip without holding the peer's A4.
        val forged = signedTip(physicalTime = 100L, signingKey = "wrong-key".encodeToByteArray())
        tracker.observe(forged)

        val warning = assertNotNull(tracker.warning.value)
        assertTrue(warning is DivergenceWarning.SignatureMismatch)
        assertEquals(peerId, warning.peerDeviceId)
    }

    @Test
    fun emptyTip_afterRealTip_doesNotRegress() {
        // Repro of the observed live warning: the tracker sees a real tip (physical
        // time ≈ now), then the relay serves an all-zero "no tip yet" sentinel for the
        // same device. Physical-time 0 is trivially < the real tip, which used to fire
        // a false-positive Regression ("Sync issue detected"). The empty-tip guard must
        // treat it as transient instead.
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 1_783_782_873_453L))
        tracker.observe(signedTip(physicalTime = 0L))  // relay's empty sentinel
        assertNull(tracker.warning.value)
    }

    @Test
    fun emptyTip_doesNotCorruptFloor() {
        // An empty tip must be a pure no-op: it neither warns nor lowers the tracked
        // floor, so a subsequent real, monotonically-advancing tip is still healthy.
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 0L))    // sentinel — skipped
        tracker.observe(signedTip(physicalTime = 300L))  // advances normally
        assertNull(tracker.warning.value)
    }

    @Test
    fun emptyTipFirst_thenRealTip_noWarning() {
        // An empty tip as the very first observation must not seed the floor at 0 (which
        // would be harmless, but the guard keeps the sentinel a strict no-op).
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 0L))
        tracker.observe(signedTip(physicalTime = 100L))
        assertNull(tracker.warning.value)
    }

    @Test
    fun unknownPeer_treatedAsTransient_noWarning() {
        // Public-key lookup returns null — peer's Device entity hasn't propagated yet.
        // Tracker must NOT cry wolf in this transient state; the next propagation tick
        // will populate the device and re-observation will then verify.
        val tracker = newTracker { null }
        tracker.observe(signedTip(physicalTime = 100L))
        assertNull(tracker.warning.value)
    }

    @Test
    fun regression_thenRecovery_autoClears() {
        // The sticky-latch fix: after a regression fires, a later tip that advances *past*
        // the pre-regression high-water mark clears the warning automatically — the tray
        // triangle must not persist for the life of the process once sync recovers.
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))  // regression → warns
        assertNotNull(tracker.warning.value)

        tracker.observe(signedTip(physicalTime = 300L))  // advances past the 200L floor
        assertNull(tracker.warning.value)
    }

    @Test
    fun regression_recoveryOnlyOnceFloorSurpassed() {
        // The regression path deliberately does NOT lower the floor (it stays at 200L), so a
        // tip that recovers but is still below the floor must NOT clear the warning — only
        // genuine forward progress past the high-water mark counts as recovery.
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))  // regression → warns
        tracker.observe(signedTip(physicalTime = 150L))  // higher than 100 but < 200 floor
        assertNotNull(tracker.warning.value)             // still latched
        tracker.observe(signedTip(physicalTime = 250L))  // now past the floor
        assertNull(tracker.warning.value)
    }

    @Test
    fun otherPeerRecovery_doesNotClearWarning() {
        // Auto-clear is scoped to the warning's own peer: a *different* peer advancing must
        // not mask a still-live divergence on the peer that actually regressed.
        val otherPeerId = "other-device"
        val otherKey = "other-key".encodeToByteArray()
        val tracker = newTracker { deviceId ->
            if (deviceId == otherPeerId) otherKey else peerPublicKey
        }
        // peer-device regresses → warns.
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))
        assertNotNull(tracker.warning.value)

        // A healthy advancing tip from a *different* peer must not clear it.
        val otherHlc = HybridLogicalClock.create(testProxy.tContext, 500L, 0, otherPeerId)
        val otherSig = stubEd25519Signature(otherKey, hlcTipCanonicalMessage(ownerId, otherPeerId, otherHlc))
        tracker.observe(SignedHlcTip.create(testProxy.tContext, ownerId, otherPeerId, otherHlc, otherSig))
        assertNotNull(tracker.warning.value)
        assertEquals(peerId, tracker.warning.value!!.peerDeviceId)
    }

    @Test
    fun regression_stampsWhenItWasFirstSeen() {
        val tracker = newTracker { peerPublicKey }
        clockMs = 5_000L
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))

        assertEquals(5_000L, assertNotNull(tracker.warning.value).observedAtMillis)
    }

    @Test
    fun repeatedRegression_keepsTheFirstStamp() {
        // ⚠⚠ The load-bearing one. The offending tip sits in the relay's single per-device slot,
        // so the *same* regression is re-derived on every poll — 120 times an hour at the 30 s
        // cadence. If each re-derivation restamped, `observedAtMillis` would read as "just now"
        // forever and no recency bound could ever demote the warning. That is precisely how the
        // tray came to present a 50-minute-old, un-actionable warning as its headline state.
        val tracker = newTracker { peerPublicKey }
        clockMs = 5_000L
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))

        clockMs = 900_000L
        tracker.observe(signedTip(physicalTime = 100L))  // same evidence, fifteen minutes later

        val warning = assertNotNull(tracker.warning.value)
        assertTrue(warning is DivergenceWarning.Regression)
        assertEquals(5_000L, warning.observedAtMillis, "a re-read of the same tip is not new evidence")
    }

    @Test
    fun differentRegression_restampsAsNewEvidence() {
        // The flip side: holding the stamp must not make it *stuck*. A regression to a different
        // HLC is a genuinely new observation and restarts the clock, or a worsening situation
        // would age out on the strength of when it first began.
        val tracker = newTracker { peerPublicKey }
        clockMs = 5_000L
        tracker.observe(signedTip(physicalTime = 300L))
        tracker.observe(signedTip(physicalTime = 100L))
        assertEquals(5_000L, assertNotNull(tracker.warning.value).observedAtMillis)

        clockMs = 9_000L
        tracker.observe(signedTip(physicalTime = 50L))  // regressed further — new evidence

        val warning = assertNotNull(tracker.warning.value)
        assertTrue(warning is DivergenceWarning.Regression)
        assertEquals(50L, warning.currentHlcPhysicalTime)
        assertEquals(9_000L, warning.observedAtMillis)
    }

    @Test
    fun repeatedSignatureMismatch_keepsTheFirstStamp() {
        // Same rule on the other variant — a relay that forges a tip keeps forging it, so the
        // mismatch re-derives every poll too.
        val tracker = newTracker { peerPublicKey }
        val forged = signedTip(physicalTime = 100L, signingKey = "wrong-key".encodeToByteArray())
        clockMs = 5_000L
        tracker.observe(forged)
        clockMs = 900_000L
        tracker.observe(forged)

        val warning = assertNotNull(tracker.warning.value)
        assertTrue(warning is DivergenceWarning.SignatureMismatch)
        assertEquals(5_000L, warning.observedAtMillis)
    }

    @Test
    fun clearWarning_resetsState() {
        val tracker = newTracker { peerPublicKey }
        tracker.observe(signedTip(physicalTime = 200L))
        tracker.observe(signedTip(physicalTime = 100L))
        assertNotNull(tracker.warning.value)
        tracker.clearWarning()
        assertNull(tracker.warning.value)
    }
}
