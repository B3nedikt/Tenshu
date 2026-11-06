package app.tenshu.reachability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ControlReachability.reason` (`core-schema-skew-surface.md` SK-1): the engine used to report a
 * schema-refused proxy and a dead channel identically, so the shell had to pick one set of words
 * for two problems with different fixes.
 *
 * `scope = null` disables the probe loop, so these drive the state directly — the point here is
 * the derivation, not the probe.
 */
class UnreachableReasonTest {

    private fun engine(
        skew: MutableStateFlow<UnreachableReason.Skew?> = MutableStateFlow(null),
        fence: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        proxyHost: ProxyHost? = ProxyHost.CONTROL,
    ) = ControlReachability(
        probe = { true },
        scope = null,
        schemaSkew = skew,
        schemaFence = fence,
        proxyHost = MutableStateFlow(proxyHost),
        controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
    )

    /**
     * ⚠⚠ Q3's false positive, and the reason `null` is not `Transport`. Before the first outcome
     * nothing has been checked; a banner rendered here would accuse a healthy device at launch.
     */
    @Test
    fun beforeAnyOutcomeThereIsNoReason() = runTest {
        assertNull(engine().reason.first())
    }

    @Test
    fun aReachableControlHasNoReason() = runTest {
        val reach = engine()
        reach.markReachable()

        assertNull(reach.reason.first())
    }

    @Test
    fun anUnreachableControlWithNoSkewIsATransportProblem() = runTest {
        val reach = engine()
        reach.markUnreachable()

        assertEquals(UnreachableReason.Transport, reach.reason.first())
    }

    /**
     * ⚠⚠ The load-bearing ordering. A refused announce *also* makes every probe fail, so a skewed
     * device satisfies the transport case too — resolving it the other way would put the old
     * "Can't reach Control" words back on precisely the devices this exists to fix.
     */
    @Test
    fun skewOutranksTransportWhenBothApply() = runTest {
        val skew = MutableStateFlow<UnreachableReason.Skew?>(
            UnreachableReason.Skew(UpdateTarget.CONTROL, listOf("tenshu/identity"))
        )
        val reach = engine(skew)
        reach.markUnreachable()

        assertEquals(
            UnreachableReason.Skew(UpdateTarget.CONTROL, listOf("tenshu/identity")),
            reach.reason.first(),
        )
    }

    /**
     * ⚠ A verdict that outlives its fix is worse than none: it tells the user to update something
     * they have already updated. Recovery must clear it even though the skew flow itself is still
     * holding its last value — the announce that cleared the refusal is what moves that flow, and
     * a reconnect re-announces (§6.4), but the state edge must not wait for it.
     */
    @Test
    fun recoveryClearsTheReasonEvenWhileTheSkewFlowIsStale() = runTest {
        val skew = MutableStateFlow<UnreachableReason.Skew?>(
            UnreachableReason.Skew(UpdateTarget.THIS_APP)
        )
        val reach = engine(skew)
        reach.markUnreachable()
        assertEquals(UnreachableReason.Skew(UpdateTarget.THIS_APP), reach.reason.first())

        reach.markReachable()

        assertNull(reach.reason.first())
    }

    /** An engine wired without a skew source behaves exactly as it did before SK-1. */
    @Test
    fun withoutASkewSourceEveryOutageIsTransport() = runTest {
        val reach = ControlReachability(probe = { true }, scope = null, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        reach.markUnreachable()

        assertEquals(UnreachableReason.Transport, reach.reason.first())
    }

    // MARK: - SK-3, the fence (skew A)

    /**
     * ⚠⚠ The case that has never had a surface, and the reason the fence could not simply be
     * bolted onto SK-1's ordering. The fence gates `pullAllowed` only: the device answers every
     * read, so `state` is `true` and SK-1's leading `reachable != false -> null` arm returned
     * before the fence was ever consulted. A fenced device looked perfectly healthy while
     * silently receiving nothing.
     */
    @Test
    fun aFencedDeviceReportsFencedEvenThoughControlIsReachable() = runTest {
        val reach = engine(fence = MutableStateFlow(listOf("tenshu/identity")))
        reach.markReachable()

        assertEquals(
            UnreachableReason.Fenced(UpdateTarget.CONTROL, listOf("tenshu/identity")),
            reach.reason.first(),
        )
    }

    /**
     * ⚠⚠ There is no *direction* to a fence — a fenced device is always the one behind — so the
     * binary to name comes from who owns the runtime. Under route-1 that is the app itself, and
     * naming Control there would send the user to update something that is already current.
     */
    @Test
    fun underRouteOneAFenceNamesTheAppItself() = runTest {
        val reach = engine(
            fence = MutableStateFlow(listOf("tenshu/timeline")),
            proxyHost = ProxyHost.THIS_APP,
        )
        reach.markReachable()

        assertEquals(
            UpdateTarget.THIS_APP,
            (reach.reason.first() as UnreachableReason.Fenced).update,
        )
    }

    @Test
    fun anEmptyFenceListIsNotAFence() = runTest {
        val reach = engine(fence = MutableStateFlow(emptyList()))
        reach.markReachable()

        assertNull(reach.reason.first())
    }

    /**
     * ⚠ A hard refusal outranks a fence: skew B means nothing loads at all, while a fenced device
     * still serves everything it already has. Showing the milder problem would bury the one that
     * is actually stopping the app.
     */
    @Test
    fun aRefusedSessionOutranksAFence() = runTest {
        val reach = engine(
            skew = MutableStateFlow(UnreachableReason.Skew(UpdateTarget.CONTROL)),
            fence = MutableStateFlow(listOf("tenshu/identity")),
        )
        reach.markUnreachable()

        assertEquals(UnreachableReason.Skew(UpdateTarget.CONTROL), reach.reason.first())
    }

    /**
     * ⚠⚠ SK-2's honest-unknown case. No host published a `RuntimeIdentity` row, so neither answer
     * may be claimed: "update Control" is wrong under route 1, "update this app" is wrong on
     * Android and desktop. The fence is still reported — it is real — with no app named.
     */
    @Test
    fun anUndeclaredHostReportsTheFenceWithoutNamingAnApp() = runTest {
        val reach = engine(fence = MutableStateFlow(listOf("tenshu/identity")), proxyHost = null)
        reach.markReachable()

        val reason = reach.reason.first()
        assertEquals(UnreachableReason.Fenced(null, listOf("tenshu/identity")), reason)
    }

    // MARK: - ADR 0025, Control is not installed

    /**
     * ⚠⚠ The defect ADR 0025 was written about. Folded into [UnreachableReason.Transport] this
     * state renders as *"open Control and unlock it, then come back"* — an instruction naming an
     * app that is not on the device.
     *
     * ⚠ The probe loop has to run for this: the presence answer is refreshed on the probe's
     * cadence, so `scope = null` (the other tests here) never asks. That is also why the check
     * happens **before** the first wait — a banner is on screen while it waits.
     */
    @Test
    fun anAbsentControlIsNotInstalledRatherThanTransport() = runTest {
        val reach = ControlReachability(
            probe = { false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlInstalled = { false },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        reach.markUnreachable()
        runCurrent()

        assertEquals(UnreachableReason.NotInstalled, reach.reason.first())
    }

    /**
     * ⚠⚠ The honest degrade, and the reason the probe is `Boolean?` rather than `Boolean`. Desktop
     * cannot answer on Windows or Linux, and a Spotlight that is not indexing cannot answer on
     * macOS. Reading that ignorance as "missing" would invite a user to install an app they
     * already have — the mirror of the defect above.
     */
    @Test
    fun aProbeThatCannotTellLeavesTheOutageAsTransport() = runTest {
        val reach = ControlReachability(
            probe = { false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlInstalled = { null },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        reach.markUnreachable()
        runCurrent()

        assertEquals(UnreachableReason.Transport, reach.reason.first())
    }

    /** ⚠ A probe that throws is ignorance, not absence — same rule as `null`, louder failure mode. */
    @Test
    fun aThrowingProbeIsReadAsCannotTell() = runTest {
        val reach = ControlReachability(
            probe = { false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlInstalled = { error("no package manager here") },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        reach.markUnreachable()
        runCurrent()

        assertEquals(UnreachableReason.Transport, reach.reason.first())
    }

    /**
     * ⚠⚠ The recovery this case actually has: the user leaves, installs Control, and comes back.
     * A presence answer cached at launch would argue with a device that has already been fixed, so
     * the probe re-asks on every tick.
     */
    @Test
    fun installingControlWhileTheBannerIsUpClearsTheCase() = runTest {
        var installed = false
        val reach = ControlReachability(
            probe = { installed },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlInstalled = { installed },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(150); runCurrent()
        assertEquals(UnreachableReason.NotInstalled, reach.reason.first())

        installed = true
        advanceTimeBy(150); runCurrent()

        assertNull(reach.reason.first())
    }

    /**
     * ⚠⚠ Precedence, stated rather than assumed. A missing Control cannot in fact produce a skew —
     * it answers no announce — but ordering the two explicitly means the answer does not depend on
     * that staying true.
     */
    @Test
    fun skewStillOutranksNotInstalled() = runTest {
        val skew = MutableStateFlow<UnreachableReason.Skew?>(UnreachableReason.Skew(UpdateTarget.CONTROL))
        val reach = ControlReachability(
            probe = { false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            schemaSkew = skew,
            controlInstalled = { false },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        reach.markUnreachable()
        runCurrent()

        assertEquals(UnreachableReason.Skew(UpdateTarget.CONTROL), reach.reason.first())
    }

    /**
     * ⚠ An engine wired without the probe behaves exactly as it did before ADR 0025 — the property
     * that lets six apps adopt the parameter one at a time instead of in one commit.
     */
    @Test
    fun withoutAPresenceProbeEveryOutageIsStillTransport() = runTest {
        val reach = ControlReachability(probe = { false }, scope = backgroundScope, probeIntervalMs = 100, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        reach.markUnreachable()
        runCurrent()

        assertEquals(UnreachableReason.Transport, reach.reason.first())
    }
}
