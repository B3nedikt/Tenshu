package app.tenshu.reachability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UnreachableReason.NotSetUp] — Control installed and answering, no realm on this device
 * (`ADR 0025` §Decision 8).
 *
 * ⚠ Every test lets the probe run to `state == true` first, because that is the only branch the
 * case lives on: the proxy opens its store whether or not a realm exists, so a no-realm Control is
 * *reachable*, which is exactly why a transport-only check cannot surface it.
 */
class NotSetUpTest {

    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceTimeBy(150)
        runCurrent()
    }

    @Test
    fun aReachableControlWithNoRealmIsNotSetUp() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        settle()

        assertEquals(true, reach.state.value)
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())
    }

    @Test
    fun aReachableControlWithARealmHasNoReason() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { true },
        )
        settle()

        assertNull(reach.reason.first())
    }

    /**
     * ⚠⚠ The honest degrade (§Decision 4's rule). A status read that failed, or an app that does
     * not register `:sync-service`, cannot tell — and must leave the shell exactly as it was rather
     * than wall a set-up user out of their own library.
     */
    @Test
    fun aRealmProbeThatCannotTellChangesNothing() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { null },
        )
        settle()

        // Cannot tell is never NotSetUp — and, with a realm probe wired, it is the non-interactive
        // checking state rather than the shell (ADR 0025 §8.6).
        assertEquals(UnreachableReason.Checking, reach.reason.first())
    }

    /**
     * ⚠⚠ The reason is readable SYNCHRONOUSLY, before anything has collected
     * or any loop has run. With a wired probe and nothing known yet that is Checking — so an app
     * root's very first frame is the non-interactive gate, never the shell. (Unwired: null.)
     */
    @Test
    fun theReasonIsCheckingSynchronouslyBeforeAnythingHasRun() = runTest {
        val wired = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { true },
        )
        // No `settle()`, no collector: the engine's loops have not run a single step.
        assertEquals(UnreachableReason.Checking, wired.reason.value, "the first frame is the checking gate")

        val unwired = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        assertNull(unwired.reason.value, "no probe, no gate: the shell")

        settle()
        assertNull(wired.reason.value, "and it follows the verdict: a realm → the shell")
    }

    /**
     * ⚠⚠ "Try again" must ask again, not only restart a timer. With the realm
     * read unable to answer and the backoff exhausted, the next read is a whole poll interval away —
     * `recheck()` makes it happen now.
     */
    @Test
    fun recheckTriggersARealmReadNow() = runTest {
        var reads = 0
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 600_000,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { reads++; null },
        )
        // Before the first probe has answered (it is a whole interval away), a tap wakes the PROBE:
        // Control answers, and the first realm read follows.
        runCurrent()
        assertEquals(0, reads, "nothing has been asked yet")
        reach.recheck()
        runCurrent()
        assertEquals(true, reach.state.value, "the tap woke the probe")
        assertEquals(1, reads, "and the realm was read")

        // Past the short PENDING backoff (250 ms, 500 ms, 1 s, 2 s); the next read is 10 minutes off.
        advanceTimeBy(10_000); runCurrent()
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value)
        val before = reads
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before, reads, "nothing re-reads on its own inside the poll interval")

        // With Control answering, a tap cuts the realm-read loop's wait short.
        reach.recheck()
        runCurrent()
        assertEquals(before + 1, reads, "the tap triggered a realm read")
    }

    /**
     * ⚠⚠ A realm read hung in flight must not make "Try again" wait out the
     * read's 15 s timeout. `recheck()` cancels the hung read and a fresh one starts at once. The
     * cancelled read is no answer — the verdict stays PENDING and writes stay held — and only the
     * fresh read's answer lands.
     */
    @Test
    fun recheckInterruptsARealmReadHungInFlight() = runTest {
        var reads = 0
        var cancelledReads = 0
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired {
                if (++reads == 1) {
                    try {
                        kotlinx.coroutines.awaitCancellation() // the first read hangs
                    } finally {
                        cancelledReads++
                    }
                }
                true
            },
        )
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, reads, "one read, hung in flight")
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value)

        val tappedAt = testScheduler.currentTime
        reach.recheck()
        runCurrent()
        assertEquals(tappedAt, testScheduler.currentTime, "no virtual time passed")
        assertEquals(1, cancelledReads, "the hung read was cancelled")
        assertEquals(2, reads, "a fresh read started at the tap, not 15 s later")
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value, "and only the fresh read's answer landed")
    }

    /** The pair: a recheck that interrupts a read is never itself an answer — a held verdict stays held. */
    @Test
    fun anInterruptedRealmReadIsNoAnswer() = runTest {
        var reads = 0
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { reads++; kotlinx.coroutines.awaitCancellation() },
        )
        advanceTimeBy(1_000); runCurrent()
        reach.recheck()
        runCurrent()
        assertEquals(2, reads, "the fresh read is in flight")
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value, "no answer: still PENDING")
        assertTrue(reach.realmWritesHeld, "and writes stay held")
        assertEquals(UnreachableReason.Checking, reach.reason.value)
    }

    @Test
    fun aThrowingRealmProbeIsReadAsCannotTell() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { error("No schema registered for service id: tenshu/sync/service") },
        )
        settle()

        // Cannot tell is never NotSetUp — and, with a realm probe wired, it is the non-interactive
        // checking state rather than the shell (ADR 0025 §8.6).
        assertEquals(UnreachableReason.Checking, reach.reason.first())
    }

    /**
     * ⚠⚠ The recovery this case has: the user taps *Open Control*, sets it up, and switches back.
     * The wall must lift without a relaunch, so a `false` answer is re-read on the probe cadence.
     */
    @Test
    fun settingControlUpWhileTheWallIsUpClearsIt() = runTest {
        var hasRealm = false
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        settle()
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())

        hasRealm = true
        settle()

        assertNull(reach.reason.first())
    }

    /**
     * ⚠⚠ Onboarding must not flicker the wall. The owner appears mid-onboarding and the wall lifts;
     * an answer that wobbles back afterwards (here: any later `false`) is not consulted until the
     * next reconnect, so the user who just finished setting Control up never sees the wall return.
     */
    @Test
    fun onceSetUpTheWallStaysDownUntilTheNextReconnect() = runTest {
        val answers = ArrayDeque(listOf(false, false, true, false, false))
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answers.removeFirstOrNull() ?: false },
        )
        settle()
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())

        val seen = mutableListOf<UnreachableReason?>()
        repeat(10) {
            advanceTimeBy(100); runCurrent()
            seen += reach.reason.first()
        }
        // Down once the owner appeared, and never back up while Control stayed reachable.
        val firstClear = seen.indexOfFirst { it == null }
        assertTrue(firstClear >= 0, "the wall never lifted: $seen")
        assertTrue(seen.drop(firstClear).all { it == null }, "the wall came back: $seen")
    }

    /** ⚠ A healthy device pays one read per reconnect, not one per tick. */
    @Test
    fun aKnownRealmIsNotReReadUntilTheNextRecovery() = runTest {
        var reads = 0
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { reads++; true },
        )
        settle()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, reads)

        // An outage and a recovery re-enter: the realm may have changed while Control was away.
        reach.markUnreachable()
        runCurrent()
        settle()
        assertEquals(true, reach.state.value)
        assertEquals(2, reads)
    }

    /**
     * ⚠⚠ The answer is a fact read *from* Control, so it is stale the moment Control stops
     * answering — the transport reasons own the words then, and NotInstalled still outranks.
     */
    @Test
    fun anOutageReplacesNotSetUpWithTheTransportReasons() = runTest {
        var up = true
        var installed: Boolean? = true
        val reach = ControlReachability(
            probe = { up },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlInstalled = { installed },
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        settle()
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())

        up = false
        reach.markUnreachable()
        runCurrent()
        assertEquals(UnreachableReason.Transport, reach.reason.first())

        installed = false
        settle()
        assertEquals(UnreachableReason.NotInstalled, reach.reason.first())
    }

    /** ⚠ A session that cannot read answers cannot trust this one either. */
    @Test
    fun anUnreadableAnswerOutranksNotSetUp() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            answerUnreadable = MutableStateFlow(true),
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        settle()

        assertEquals(UnreachableReason.Skew(UpdateTarget.BOTH), reach.reason.first())
    }

    /** A fence presupposes a realm to be behind; with none, "not set up" is the true sentence. */
    @Test
    fun notSetUpOutranksAFence() = runTest {
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            schemaFence = MutableStateFlow(listOf("tenshu/file/service")),
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        settle()

        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())
    }

    /** ⚠ Absent the probe (iOS route-1, tests, previews), the case never fires. */
    @Test
    fun withoutARealmProbeTheCaseNeverFires() = runTest {
        val reach = ControlReachability(probe = { true }, scope = backgroundScope, probeIntervalMs = 100, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        settle()

        assertNull(reach.reason.first())
    }

    // ---- §Decision 8.6: the realm verdict as a write gate ----

    /** ⚠ Not asked yet is not "cannot tell": a launch pass must wait, not run. */
    @Test
    fun theVerdictStartsPendingAndSettlesToAbsent() = runTest {
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value)
        assertTrue(reach.realmWritesHeld)
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)
        assertTrue(reach.realmWritesHeld)
    }

    /** Unwired (iOS route-1, tests): nothing held — the only standing UNKNOWN. */
    @Test
    fun anUnwiredProbeHoldsNothing() = runTest {
        val unwired = ControlReachability(probe = { true }, scope = backgroundScope, probeIntervalMs = 100, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        assertEquals(RealmVerdict.UNKNOWN, unwired.realmVerdict.value)
        assertFalse(unwired.realmWritesHeld)
        unwired.awaitRealmWritable() // returns
    }

    /**
     * ⚠⚠ Right after a Control restart, an app's realm read lands
     * before its schema announce is admitted and FAILS. A failed read is not an answer, so it never
     * moves PENDING — writes stay held — and the next read that does answer releases them. The
     * retry is on a short backoff, not the full probe interval.
     */
    @Test
    fun aFailedRealmReadKeepsPendingUntilTheNextAnswerReleases() = runTest {
        var reads = 0
        var answers: Boolean? = null // "not admitted yet" → the read throws
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 5_000,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired {
                reads++
                answers ?: error("session has no admitted schema announce")
            },
        )
        var releases = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge(since = 0) { releases++ } }
        advanceTimeBy(5_100); runCurrent() // the first probe → reachable → the first realm read fails
        assertEquals(true, reach.state.value)
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value, "a failed read is not an answer")
        assertTrue(reach.realmWritesHeld)
        assertEquals(0, releases)

        // The session is admitted; the next retry comes on the short backoff, well inside the 5 s poll.
        answers = true
        val readsBefore = reads
        advanceTimeBy(1_000); runCurrent()
        assertTrue(reads > readsBefore, "retried on the backoff, not after a whole probe interval")
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)
        assertEquals(1, releases, "released by the answer, and only by it")
    }

    /** ⚠⚠ One failed re-read must neither release held writes nor drop the wall. */
    @Test
    fun aKnownAbsenceSurvivesAFailedReRead() = runTest {
        var answer: () -> Boolean? = { false }
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer() },
        )
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)

        answer = { error("transient") }
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())
    }

    /**
     * ⚠⚠ The recovery race. `onRecovered` (Fit's outbox drain) fires on the probe's false → true
     * flip, before the realm read has answered — it must observe PENDING, never the outage's
     * UNKNOWN, or it writes into a no-realm store on the exact edge the gate exists for.
     */
    @Test
    fun onRecoveredObservesAPendingVerdictNotTheOutagesUnknown() = runTest {
        var up = false
        val seenAtRecovery = mutableListOf<RealmVerdict>()
        lateinit var reach: ControlReachability
        reach = ControlReachability(
            probe = { up }, scope = backgroundScope, probeIntervalMs = 100,
            onRecovered = { seenAtRecovery += reach.realmVerdict.value },
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { false },
        )
        settle()
        // ⚠ Never answered, so still PENDING: an outage is not an answer to the realm question
        // (UNKNOWN here would mean the gate opened).
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value, "unreachable before any realm read → still pending")

        up = true
        settle()
        assertEquals(listOf(RealmVerdict.PENDING), seenAtRecovery)
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)
    }

    /** The resumption hook fires on held → writable edges only, never on subscription. */
    @Test
    fun theWritableEdgeFiresWhenARealmAppears() = runTest {
        var hasRealm = false
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        var edges = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge { edges++ } }
        settle()
        assertEquals(0, edges, "absent: held, no edge")

        hasRealm = true
        settle()
        assertEquals(1, edges, "a realm appeared: one edge")
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)

        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, edges, "no edge while it stays present")
    }

    /**
     * ⚠⚠ ABSENT, then an organic `markUnreachable()`
     * (Fit and Timeline call it on operation failures over a live proxy), then the reconnect — and
     * still no realm. Nothing about that is evidence of a realm, so the writes stay held: no edge,
     * and ABSENT at every step. Paired with the release that IS allowed: a read that sees a realm.
     */
    @Test
    fun anOutageAndReconnectWithNoRealmNeverReleaseHeldWrites() = runTest {
        var hasRealm = false
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        var edges = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge { edges++ } }
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)

        reach.markUnreachable()
        runCurrent()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value, "an outage is not a realm")
        assertTrue(reach.realmWritesHeld)

        settle() // the probe answers, Control is back — with no realm
        assertEquals(true, reach.state.value)
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value, "a reconnect is not a realm")
        assertEquals(0, edges, "nothing released")

        hasRealm = true
        settle()
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)
        assertEquals(1, edges, "released by the read that saw a realm, and only by it")
    }

    /** The same rule through the other outage route: the transport dropping and coming back. */
    @Test
    fun aTransportDropAndReconnectWithNoRealmNeverReleaseHeldWrites() = runTest {
        val connected = MutableStateFlow(true)
        var answer: () -> Boolean? = { false }
        val reach = ControlReachability(
            probe = { connected.value }, scope = backgroundScope, probeIntervalMs = 100,
            transportConnected = connected,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer() },
        )
        var edges = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge { edges++ } }
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)

        connected.value = false
        runCurrent()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)

        // Back, and the first re-read cannot tell: still not a realm.
        answer = { null }
        connected.value = true
        settle()
        assertEquals(true, reach.state.value)
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value, "'cannot tell' does not leave ABSENT")
        assertEquals(0, edges)
    }

    /**
     * ⚠⚠ Subscribe before the initial work. The marker is taken first;
     * the release then lands BEFORE the collector is even running (the window an initial drain
     * occupies). It must still fire, once.
     */
    @Test
    fun aReleaseBeforeTheCollectorStartsIsNotLost() = runTest {
        var hasRealm = false
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        val since = reach.realmReleaseMarker // taken before any work, as the callers do
        settle()
        assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)
        hasRealm = true
        settle()
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)

        var runs = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge(since) { runs++ } }
        runCurrent()
        assertEquals(1, runs, "the release that landed before subscription still drains")
    }

    /**
     * Releases arriving while the action runs coalesce into exactly ONE follow-up run, and the runs
     * never overlap — the shape that makes "a release during the initial drain" drain again
     * afterwards without a concurrent second drain.
     */
    @Test
    fun releasesDuringAnActionCoalesceIntoOneFollowUpRun() = runTest {
        var hasRealm = false
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        val since = reach.realmReleaseMarker
        val firstRunMayFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var runs = 0
        var concurrent = 0
        var maxConcurrent = 0
        backgroundScope.launch {
            reach.onEachRealmWritableEdge(since) {
                concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
                runs++
                if (runs == 1) firstRunMayFinish.await()
                concurrent--
            }
        }
        settle()
        hasRealm = true
        settle() // release #1 → first run starts and blocks
        assertEquals(1, runs)

        // Two more releases while it is still running: outage + recovery (PENDING → PRESENT) twice.
        reach.markUnreachable(); runCurrent(); settle()
        reach.markUnreachable(); runCurrent(); settle()
        assertEquals(1, runs, "still inside the first run")

        firstRunMayFinish.complete(Unit)
        runCurrent()
        assertEquals(2, runs, "exactly one follow-up for everything that landed during the first")
        assertEquals(1, maxConcurrent, "runs never overlap")
    }

    /**
     * ⚠⚠ The launch verdict is PENDING while the first realm read is in
     * flight, and an organic `markUnreachable()` in that window must not open the gate — only the
     * realm read answering may. Paired with the read then answering PRESENT, which releases.
     */
    @Test
    fun pendingSurvivesAnOrganicFailureUntilTheRealmReadAnswers() = runTest {
        val readMayAnswer = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { readMayAnswer.await() },
        )
        var releases = 0
        backgroundScope.launch { reach.onEachRealmWritableEdge(since = 0) { releases++ } }
        settle()
        assertEquals(true, reach.state.value)
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value, "the first realm read is still in flight")

        reach.markUnreachable() // an operation failed somewhere
        runCurrent()
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value, "an outage is not an answer")
        assertTrue(reach.realmWritesHeld)
        assertEquals(0, releases)

        settle() // reachable again; the read is asked again and now answers
        readMayAnswer.complete(true)
        runCurrent()
        assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)
        assertEquals(1, releases, "released by the read's answer, and only by it")
    }

    /**
     * ⚠⚠ While Control answers but the realm question has not been answered,
     * the roots must not render the shell — its own writes (an upload, a save) would land in a
     * Control that may have no realm. The engine reports `Checking` for exactly that window, and
     * stops reporting it the moment a read answers.
     */
    @Test
    fun aReachableControlWhoseRealmReadHasNotAnsweredIsChecking() = runTest {
        val readMayAnswer = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { readMayAnswer.await() },
        )
        settle()
        assertEquals(true, reach.state.value)
        assertEquals(RealmVerdict.PENDING, reach.realmVerdict.value)
        assertEquals(UnreachableReason.Checking, reach.reason.first(), "no shell while the realm is unknown")

        readMayAnswer.complete(true)
        runCurrent()
        assertNull(reach.reason.first(), "a realm → the shell")
    }

    /**
     * ⚠⚠ A device known to have no realm must keep the wall through a
     * recovery — the moment Control is back, before any re-read answers — and lose it only to a read
     * that sees a realm. (During the outage itself the transport reasons speak.)
     */
    @Test
    fun aRememberedAbsenceKeepsTheWallThroughRecovery() = runTest {
        var answer: suspend () -> Boolean? = { false }
        val reach = ControlReachability(
            probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer() },
        )
        settle()
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first())

        val reReadMayAnswer = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        answer = { reReadMayAnswer.await() } // the re-read after recovery hangs
        reach.markUnreachable()
        runCurrent()
        assertEquals(UnreachableReason.Transport, reach.reason.first(), "an outage speaks as an outage")

        settle() // Control is back; the re-read has not answered
        assertEquals(true, reach.state.value)
        assertEquals(UnreachableReason.NotSetUp, reach.reason.first(), "the wall is back before any re-read")

        reReadMayAnswer.complete(true)
        runCurrent()
        assertNull(reach.reason.first(), "only a read that sees a realm lifts it")
    }

    /**
     * ⚠⚠ PRESENT → outage → reconnect must not leave the verdict a writable UNKNOWN, or unattended
     * writers run against the newly live Control before its realm read — and a Control reset or a
     * device forgotten during the outage has no realm. A remembered PRESENT re-checks: writes held
     * from the outage until a realm read settles, while the SHELL stays (no
     * checking screen for a reconnect). The read then releases them, or walls.
     */
    @Test
    fun aReconnectHoldsUnattendedWritesUntilTheRealmReadSettles() = runTest {
        for (reReadAnswer in listOf(true, false)) {
            var answer: suspend () -> Boolean? = { true }
            val reach = ControlReachability(
                probe = { true }, scope = backgroundScope, probeIntervalMs = 100,
                controlHasRealm = RealmProbe.Wired { answer() },
            )
            settle()
            assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)
            var releases = 0
            val collector = backgroundScope.launch {
                reach.onEachRealmWritableEdge(since = reach.realmReleaseMarker) { releases++ }
            }

            val reRead = kotlinx.coroutines.CompletableDeferred<Boolean?>()
            answer = { reRead.await() }
            reach.markUnreachable()
            runCurrent()
            assertTrue(reach.realmWritesHeld, "held from the outage on — the Control may come back realm-less")

            settle() // reconnected; the re-read has not answered
            assertEquals(true, reach.state.value)
            assertEquals(RealmVerdict.RECHECKING, reach.realmVerdict.value)
            assertTrue(reach.realmWritesHeld, "unattended writes wait for the re-read")
            assertNull(reach.reason.first(), "the shell stays during a reconnect's re-check")
            assertEquals(0, releases)

            reRead.complete(reReadAnswer)
            runCurrent()
            if (reReadAnswer) {
                assertEquals(RealmVerdict.PRESENT, reach.realmVerdict.value)
                assertFalse(reach.realmWritesHeld)
                assertEquals(1, releases, "released by the re-read")
            } else {
                assertEquals(RealmVerdict.ABSENT, reach.realmVerdict.value)
                assertEquals(UnreachableReason.NotSetUp, reach.reason.first(), "the realm is gone: the wall")
                assertEquals(0, releases)
            }
            collector.cancel()
        }
    }

    @Test
    fun waitingWillNotFixIt() {
        assertFalse(waitingWillFix(UnreachableReason.NotSetUp))
    }
}

/** The one reading of Control's answers that may say "no realm" ([realmPresence]). */
class RealmPresenceTest {

    @Test
    fun aSuccessfulReadWithNoOwnerIsNoRealm() {
        assertEquals(false, realmPresence(isLocked = false, ownerId = null))
    }

    @Test
    fun anOwnerIsARealm() {
        assertEquals(true, realmPresence(isLocked = false, ownerId = "3f9a…owner"))
    }

    /** ⚠ Present-but-empty is not evidence of absence: cannot tell, so nothing walls. */
    @Test
    fun aBlankOwnerCannotTell() {
        assertNull(realmPresence(isLocked = false, ownerId = ""))
        assertNull(realmPresence(isLocked = false, ownerId = "  "))
    }

    /**
     * ⚠⚠ Control's router checks the lock before the owner, and so does this. A sealed wrapper means
     * something was set up here even when the owner slot is empty (a wrapper that predates the
     * persisted owner), and walling that user as "not set up" is the defect this ordering prevents.
     */
    @Test
    fun aLockedDeviceIsNeverNotSetUpEvenWithNoOwnerSlot() {
        assertEquals(true, realmPresence(isLocked = true, ownerId = null))
        assertEquals(true, realmPresence(isLocked = true, ownerId = "3f9a…owner"))
    }
}
