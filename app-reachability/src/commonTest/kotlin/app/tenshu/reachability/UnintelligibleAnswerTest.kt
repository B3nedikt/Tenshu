package app.tenshu.reachability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An answer this build cannot read is **skew, not an outage**
 * (`CallOutcome.Unintelligible` → `TenshuClient.proxyAnswerUnreadable`).
 *
 * ⚠⚠ The failure this closes is a *misdiagnosis*, and misdiagnoses are the expensive kind here: the
 * proxy is running, the channel is fine, and the user was told to check a connection that works —
 * instead of to update, which is the only thing that fixes it. It reached them that way because a
 * repository's broad `catch` cannot tell "no answer" from "an answer I cannot parse", and by the
 * time a failure gets that far the distinction is gone.
 */
class UnintelligibleAnswerTest {

    private fun engine(
        answerUnreadable: MutableStateFlow<Boolean>,
        schemaSkew: MutableStateFlow<UnreachableReason.Skew?> = MutableStateFlow(null),
    ) = ControlReachability(
        probe = { true },
        scope = null,
        schemaSkew = schemaSkew,
        answerUnreadable = answerUnreadable,
        controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
    )

    @Test
    fun anUnreadableAnswerReadsAsSkewEvenThoughTheProxyIsAnswering() = runTest {
        // ⚠⚠ The load-bearing case, and the reason this is evaluated on the REACHABLE branch. The
        // proxy answered — so reachability is fine — and gating the verdict on `reachable == false`
        // would give it no surface at all. That is the SK-1 mistake `ControlReachability` already
        // records once, for the fence.
        val unreadable = MutableStateFlow(false)
        val engine = engine(unreadable)

        assertNull(engine.reason.first(), "a healthy proxy reports nothing")

        unreadable.value = true

        assertEquals(
            UnreachableReason.Skew(UpdateTarget.BOTH),
            engine.reason.first(),
            "an unreadable answer must surface as skew, not as an outage",
        )
    }

    @Test
    fun aRefusedAnnouncesVerdictWinsBecauseItKnowsWhichSideIsBehind() = runTest {
        // ⚠ Both routes reach "skew", but only one knows the target. An unreadable response proves
        // the two disagree and nothing more, so it must not displace a verdict computed by
        // comparing two version vectors.
        //
        // ⚠⚠ Written as a REFUSED announce — `markUnreachable` — because that is the only way the
        // two coexist. A refusal is what sets the announced verdict, and a refusal is what makes
        // the engine unreachable; an *admitted* session that later meets an unknown type has no
        // announced verdict to lose. The first version of this test asserted a state that cannot
        // occur, and passed only because the two routes were collapsed into one value.
        val announced = UnreachableReason.Skew(UpdateTarget.CONTROL, serviceIds = listOf("svc"))
        val engine = engine(
            answerUnreadable = MutableStateFlow(true),
            schemaSkew = MutableStateFlow(announced),
        )
        engine.markUnreachable()

        assertEquals(announced, engine.reason.first())
    }

    @Test
    fun aReadableAnswerClearsIt() = runTest {
        // ⚠ Skew is a *condition*, not an event: it lasts until something is updated, and one
        // response this build understood is proof that it is over.
        val unreadable = MutableStateFlow(true)
        val engine = engine(unreadable)
        assertEquals(UnreachableReason.Skew(UpdateTarget.BOTH), engine.reason.first())

        unreadable.value = false

        assertNull(engine.reason.first())
    }

    @Test
    fun anEngineWithNoSuchSourceBehavesExactlyAsBefore() = runTest {
        // ⚠ Every existing caller passes nothing, and must be unaffected — tests, previews, and the
        // six apps not yet wired to it.
        val engine = ControlReachability(probe = { true }, scope = null, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        assertNull(engine.reason.first())
    }
}
