package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.askMia
import org.tenshu.routine.Routine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RE-5 — Mia can fire a routine, behind MQ-D7's confirmation cell.
 *
 * ⚠⚠ The property under test is the same one `AskMiaActionTest` pins for devices: **asking never
 * fires**. A routine is the largest actuation Mia can perform — one utterance can change every
 * light in the house — so the split between the turn that resolves and the tap that runs matters
 * more here than anywhere else, not less.
 */
class AskMiaRoutineTest {

    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun TestProxy.seedRoutine(
        id: String,
        name: String,
        enabled: Boolean = true,
        state: String = Routine.STATE_ACTIVE,
    ) {
        executionContext.saveData(
            Routine.create(
                context = tContext,
                routineId = id,
                name = name,
                enabled = enabled,
                state = state,
                timeZoneId = "UTC",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                programJson = "{}",
                programVersion = 1L,
            )
        )
    }

    @Test
    fun askingToRunARoutineResolvesItButFiresNothing() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Good morning")

        val answer = ask(proxy, "run my good morning routine")

        assertEquals(MiaAnswer.VERDICT_CONFIRM, answer.verdict.get())
        val action = assertNotNull(answer.action)
        // ⚠⚠ The cell names the RESOLVED routine, not the fragment typed.
        assertEquals("Run Good morning", action.title.get())
    }

    /**
     * ⚠⚠ **Never guesses between candidates.** Two routines matching "evening" is exactly where a
     * keyword router picks one and is confidently wrong — and unlike a wrong search result, the
     * cost is a house that changes.
     */
    @Test
    fun twoMatchesAskWhichRatherThanPickingOne() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Evening wind down")
        proxy.seedRoutine("r2", "Evening lights")

        val answer = ask(proxy, "run my evening routine")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action, "an ambiguous match must offer nothing to tap")
        val text = answer.explanation.get()
        assertTrue(text.contains("Evening wind down") && text.contains("Evening lights"), text)
    }

    @Test
    fun anUnknownNameSaysSoRatherThanRunningTheNearestThing() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Good morning")

        val answer = ask(proxy, "run my bedtime routine")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }

    /**
     * ⚠⚠ **Refused before the cell.** `RunRoutineNow` honours `STATE_SUSPENDED`, so a button here
     * would be guaranteed to fail — the rule `eventConfirmation` set for a missing default
     * calendar. A cell whose tap cannot work is worse than an honest no.
     */
    @Test
    fun aSuspendedRoutineIsRefusedBeforeTheCellRatherThanFailingOnTap() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Good morning", state = Routine.STATE_SUSPENDED)

        val answer = ask(proxy, "run my good morning routine")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action, "a tap that is guaranteed to fail must not be offered")
        assertTrue(answer.explanation.get().contains("suspended"), answer.explanation.get())
    }

    /**
     * ⚠ A *disabled* routine still runs — `RunRoutineNow` ignores `enabled`, because running one
     * on purpose is how you test it while authoring. The cell says so rather than hiding it: "off"
     * on the Automations tab and "ran anyway" would otherwise read as a contradiction.
     */
    @Test
    fun aDisabledRoutineStillOffersToRun_andTheCellSaysItIsOff() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Good morning", enabled = false)

        val answer = ask(proxy, "run my good morning routine")

        assertEquals(MiaAnswer.VERDICT_CONFIRM, answer.verdict.get())
        val action = assertNotNull(answer.action)
        assertTrue(action.detail.get().contains("off"), action.detail.get())
    }
}
