package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.home.device.HomeDevice
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.mia.plan.MiaActionOffer
import org.tenshu.preference.RealmSetting
import org.tenshu.routine.Routine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A runtime that offers no action — the iOS route-1 wiring (`mia-query-layer.md` §19).
 *
 * ⚠⚠ **Every refusal here has an [MiaActionOffer.OFFERED] twin over the SAME fixture and the SAME
 * utterance.** Without it these would pass on a harness that could never offer an action in the
 * first place — a realm with no such device, no default calendar, no write grant — and "nothing
 * was offered" would be a property of the fixture rather than of the runtime.
 *
 * ⚠ The token is asserted against the registry, not only against the answer: an answer with no
 * `action` proves the cell was not *shown*, and the weaker form of this feature is exactly a
 * hidden button over a live token.
 */
class AskMiaActionOfferTest {

    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    /** One realm in which all three actions resolve: a device, a routine, a writable calendar. */
    private fun TestProxy.seedEverythingAnActionNeeds() {
        executionContext.saveData(
            HomeDevice.create(
                context = tContext,
                deviceId = "d1",
                bridgeId = "bridge",
                kind = "light",
                name = "Kitchen light",
                room = "Kitchen",
                joinedAtEpochMs = 1L,
            )
        )
        executionContext.saveData(
            Routine.create(
                context = tContext,
                routineId = "r1",
                name = "Good morning",
                enabled = true,
                state = Routine.STATE_ACTIVE,
                timeZoneId = "UTC",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                programJson = "{}",
                programVersion = 1L,
            )
        )
        executionContext.saveData(
            Calendar.create(context = tContext, calendarId = "cal-1", name = "Personal", colorArgb = 0)
        )
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = CalendarPreferences.DefaultCalendarId.id,
                value = "cal-1".toTString(tContext),
            )
        )
        // ⚠ The write grant is ON: otherwise the event utterance is refused by the grant on both
        // runtimes and the two halves could not be told apart.
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = assertNotNull(MiaSource.CALENDAR.writePreference).id,
                value = true.toTBoolean(tContext),
            )
        )
    }

    private val actionUtterances = listOf(
        "turn off the kitchen light",
        "run my good morning routine",
        "add lunch with anna tomorrow at 1pm",
    )

    @Test
    fun whereActionsAreOffered_eachUtteranceBecomesAConfirmationWithAToken() = runTest {
        actionUtterances.forEach { utterance ->
            val proxy = createMiaProxy(backgroundScope, actionOffer = MiaActionOffer.OFFERED)
            proxy.seedEverythingAnActionNeeds()

            val answer = ask(proxy, utterance)

            assertEquals(MiaAnswer.VERDICT_CONFIRM, answer.verdict.get(), utterance)
            assertNotNull(answer.action, utterance)
            assertEquals(1, proxy.miaActions.pendingCount, utterance)
        }
    }

    @Test
    fun whereActionsAreWithheld_theSameUtteranceIsRefusedTruthfully_andNoTokenExists() = runTest {
        actionUtterances.forEach { utterance ->
            val proxy = createMiaProxy(backgroundScope, actionOffer = MiaActionOffer.WITHHELD)
            proxy.seedEverythingAnActionNeeds()

            val answer = ask(proxy, utterance)

            assertNull(answer.action, utterance)
            // ⚠⚠ The structural half: nothing was resolved, so there is nothing to tap — however
            // the client chose to render the answer.
            assertEquals(0, proxy.miaActions.pendingCount, utterance)
            // ⚠⚠ Never `ANSWERED`: with no hits the chat appends "— nothing matched.", which
            // would say the light, the routine or the calendar does not exist (§14.1).
            assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get(), utterance)
            val text = answer.explanation.get()
            assertTrue(text.contains("this device"), text)
            assertTrue(text.contains("yet"), text)
            // ⚠ And not the sentence the confirm path produces when a routine refuses to run,
            // nor a claim about reachability — the two things an iPhone user was actually shown.
            assertFalse(text.contains("Control"), text)
        }
    }

    /**
     * ⚠⚠ Withheld BEFORE the name is resolved. A refusal that only appears when the device exists
     * would answer "I couldn't find a device called …" for a typo and "not on this device" for a
     * hit — two different sentences about one unavailable feature, and the first one invites the
     * user to keep trying spellings.
     */
    @Test
    fun theRefusalDoesNotDependOnWhetherTheThingExists() = runTest {
        val withheld = createMiaProxy(backgroundScope, actionOffer = MiaActionOffer.WITHHELD)
        val offered = createMiaProxy(backgroundScope, actionOffer = MiaActionOffer.OFFERED)

        val refused = ask(withheld, "turn off the kitchen light").explanation.get()
        val notFound = ask(offered, "turn off the kitchen light").explanation.get()

        assertTrue(refused.contains("this device"), refused)
        // The twin: on an offering runtime the empty realm is what gets reported.
        assertTrue(notFound.contains("couldn't find a device"), notFound)
    }

    /** ⚠⚠ Read-only is not "devices are off limits": the question about the same lamp answers. */
    @Test
    fun aQuestionAboutTheSameDeviceIsStillAnswered() = runTest {
        val proxy = createMiaProxy(backgroundScope, actionOffer = MiaActionOffer.WITHHELD)
        proxy.seedEverythingAnActionNeeds()

        val answer = ask(proxy, "is the kitchen light on")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(0, proxy.miaActions.pendingCount)
    }
}
