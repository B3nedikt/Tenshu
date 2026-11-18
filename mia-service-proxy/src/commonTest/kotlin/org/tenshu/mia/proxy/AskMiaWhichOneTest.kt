package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.toTBoolean
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaChoice
import org.tenshu.mia.MiaClarification
import org.tenshu.mia.askMia
import org.tenshu.mia.confirmMiaAction
import org.tenshu.mia.replyToMia
import org.tenshu.routine.Routine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Which one?* (`mia-query-layer.md` §25, CL-3): two devices or two routines matching a name used
 * to end in a dead-end "Which one did you mean?". Now Mia asks with a chip per candidate and hears
 * the answer.
 *
 * ⚠⚠ The answer is read against the **candidates offered**, never the realm, and a pick still
 * produces a card, never an action. The grant and (for routines) the state are read again at the
 * pick.
 */
class AskMiaWhichOneTest {

    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer =
        unwrap(proxy.miaService().askMia(utterance, now, "UTC"))

    private suspend fun reply(proxy: TestProxy, token: String, text: String): MiaAnswer =
        unwrap(proxy.miaService().replyToMia(token, text, now, "UTC"))

    private suspend fun confirm(proxy: TestProxy, token: String): MiaAnswer =
        unwrap(proxy.miaService().confirmMiaAction(token))

    private fun unwrap(result: TResult<MiaAnswer>): MiaAnswer {
        if (result is TResult.Failure<*>) error("call failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.asked(): MiaClarification {
        assertEquals(MiaAnswer.VERDICT_CLARIFY, verdict.get(), explanation.get())
        assertNull(action)
        return assertNotNull(clarification)
    }

    private fun MiaClarification.labels() = choices.toList().filterIsInstance<MiaChoice>().map { it.label.get() }

    private fun TestProxy.seedDevice(id: String, name: String, room: String = "Kitchen", capabilities: String = "") {
        executionContext.saveData(
            HomeDevice.create(
                context = tContext, deviceId = id, bridgeId = "bridge", kind = "light",
                capabilityIds = capabilities, name = name, room = room, joinedAtEpochMs = 1L,
            )
        )
    }

    private fun TestProxy.seedRoutine(id: String, name: String, state: String = Routine.STATE_ACTIVE) {
        executionContext.saveData(
            Routine.create(
                context = tContext, routineId = id, name = name, enabled = true, state = state,
                timeZoneId = "UTC", createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
                programJson = "{}", programVersion = 1L,
            )
        )
    }

    private suspend fun TestProxy.commands(): List<DeviceCommand> =
        environment().getService<HomeDeviceService>(HomeDeviceService.ID)
            .deviceCommands.getAll().getOrNull()?.toList().orEmpty().filterIsInstance<DeviceCommand>()

    /** ⭐⭐ The whole turn: which one, a chip, the card, the tap, the command. */
    @Test
    fun twoDevicesAskWhich_andAChipLeadsToTheCard() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom")

        val asked = ask(proxy, "turn off the lamp").asked()
        assertEquals(IssueDeviceCommand.Params.TARGET_DEVICE_ID, asked.param.get())
        assertEquals(MiaClarification.REASON_AMBIGUOUS, asked.reason.get())

        val bedroom = asked.choices.toList().filterIsInstance<MiaChoice>().single { it.label.get() == "Bedroom lamp" }
        val card = reply(proxy, asked.token.get(), bedroom.reply.get())
        val action = assertNotNull(card.action)
        assertEquals("Turn off Bedroom lamp", action.title.get())
        assertTrue(proxy.commands().isEmpty(), "a pick never acts")

        confirm(proxy, action.token.get())
        val command = proxy.commands().single()
        assertEquals("d2", command.targetDeviceId.get())
        assertEquals(DeviceCommand.ARG_OFF, command.argValue.get())
    }

    @Test
    fun aNameOrAnOrdinalPicksToo() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom")

        val byName = ask(proxy, "turn on the lamp").asked()
        assertEquals("Turn on Kitchen lamp", assertNotNull(reply(proxy, byName.token.get(), "the kitchen one").action).title.get())

        val byOrdinal = ask(proxy, "turn on the lamp").asked()
        val second = byOrdinal.labels()[1]
        assertEquals("Turn on $second", assertNotNull(reply(proxy, byOrdinal.token.get(), "the second one").action).title.get())
    }

    /** ⚠ A part that fits several asks again with just those, never a guess between them. */
    @Test
    fun aReplyThatFitsSeveralAsksAgainWithFewer() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Kitchen lamp strip")
        proxy.seedDevice("d3", "Bedroom lamp", room = "Bedroom")

        val all = ask(proxy, "turn off the lamp").asked()
        assertEquals(3, all.labels().size)
        val again = reply(proxy, all.token.get(), "kitchen").asked()
        assertEquals(listOf("Kitchen lamp", "Kitchen lamp strip"), again.labels().sorted())
        // …and the narrowed question is answerable in its turn.
        assertEquals("Turn off Kitchen lamp strip", assertNotNull(reply(proxy, again.token.get(), "strip").action).title.get())
    }

    /** ⚠⚠ Only a candidate answers: anything else is a new question, and nothing is acted on. */
    @Test
    fun aReplyThatIsNoCandidateIsANewQuestion() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom")
        proxy.seedDevice("d3", "Hall heater", room = "Hall")

        val asked = ask(proxy, "turn off the lamp").asked()
        // The hall heater exists in the realm, but it was not offered.
        val answer = reply(proxy, asked.token.get(), "hall heater")
        assertNotEquals(MiaAnswer.VERDICT_CLARIFY, answer.verdict.get())
        assertTrue(answer.action?.title?.get()?.contains("lamp") != true, "never one of the offered lamps")
        assertTrue(proxy.commands().isEmpty())
    }

    /** ⚠⚠ The grant is read at the pick: devices revoked after the question are refused. */
    @Test
    fun aGrantRevokedBeforeThePickIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom")
        val asked = ask(proxy, "turn off the lamp").asked()

        proxy.executionContext.saveData(
            org.tenshu.preference.RealmSetting.create(
                context = proxy.tContext,
                preferenceId = org.tenshu.mia.MiaSource.DEVICES.preference.id,
                value = false.toTBoolean(proxy.tContext),
            )
        )
        val answer = reply(proxy, asked.token.get(), "bedroom")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }

    /** Two devices with one name are told apart by their room. */
    @Test
    fun collidingNamesCarryTheirRoom() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Lamp", room = "Kitchen")
        proxy.seedDevice("d2", "Lamp", room = "Bedroom")

        assertEquals(listOf("Lamp (Bedroom)", "Lamp (Kitchen)"), ask(proxy, "turn off the lamp").asked().labels().sorted())
    }

    @Test
    fun twoRoutinesAskWhich_andThePickLeadsToTheCard() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Evening wind down")
        proxy.seedRoutine("r2", "Evening lights")

        val asked = ask(proxy, "run my evening routine").asked()
        val card = reply(proxy, asked.token.get(), "wind down")
        assertEquals("Run Evening wind down", assertNotNull(card.action).title.get())
    }

    /** ⚠⚠ The state is read at the pick: a routine suspended after the question is refused. */
    @Test
    fun aRoutineSuspendedBeforeThePickIsRefused() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedRoutine("r1", "Evening wind down")
        proxy.seedRoutine("r2", "Evening lights")
        val asked = ask(proxy, "run my evening routine").asked()

        proxy.seedRoutine("r1", "Evening wind down", state = Routine.STATE_SUSPENDED)
        val answer = reply(proxy, asked.token.get(), "wind down")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("suspended"), answer.explanation.get())
    }

    // ---- dimming to a level --------------------------------------------------------

    /** ⭐ "dim … to 30 percent" ends in a `setLevel` command with "30", and only after the tap. */
    @Test
    fun dimmingSetsTheLevel_onlyAfterTheTap() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Bedroom light", room = "Bedroom", capabilities = "power level")

        val card = ask(proxy, "dim the bedroom light to 30 percent")
        val action = assertNotNull(card.action)
        assertEquals("Set Bedroom light to 30%", action.title.get())
        assertTrue(proxy.commands().isEmpty())

        confirm(proxy, action.token.get())
        val command = proxy.commands().single()
        assertEquals(DeviceCommand.VERB_SET_LEVEL, command.verb.get())
        assertEquals("30", command.argValue.get())
    }

    /** ⚠⚠ A device with no level is refused BEFORE a card: the hub would refuse after the tap. */
    @Test
    fun aDeviceThatCannotDimIsRefusedBeforeACard() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("p1", "Kitchen plug light", capabilities = "power")

        val answer = ask(proxy, "dim the kitchen plug light to 30 percent")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("can't be dimmed"), answer.explanation.get())
    }

    /** Only dimmable devices are offered, and the level survives the which-one pick. */
    @Test
    fun aWhichOnePickKeepsTheLevel() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp", capabilities = "power level")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom", capabilities = "power level")
        proxy.seedDevice("d3", "Hall lamp", room = "Hall", capabilities = "power")

        val asked = ask(proxy, "dim the lamp to 40 percent").asked()
        assertEquals(listOf("Bedroom lamp", "Kitchen lamp"), asked.labels().sorted(), "the hall lamp cannot dim")
        val card = assertNotNull(reply(proxy, asked.token.get(), "bedroom").action)
        assertEquals("Set Bedroom lamp to 40%", card.title.get())
        confirm(proxy, card.token.get())
        assertEquals("40", proxy.commands().single().argValue.get())
    }

    /**
     * ⚠⚠ The capability narrows the search before its bound (Codex review): twenty-one plain
     * lights must not crowd the one dimmable light out of the twenty rows the chain returns.
     */
    @Test
    fun manyLightsThatCannotDimDoNotHideOneThatCan() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        repeat(21) { proxy.seedDevice("p$it", "Light $it", capabilities = "power") }
        proxy.seedDevice("d1", "Light dimmable", capabilities = "power level")

        val card = ask(proxy, "dim the light to 30 percent")
        assertEquals("Set Light dimmable to 30%", assertNotNull(card.action, card.explanation.get()).title.get())
    }

    /** And a name that matches nothing at all is "couldn't find", never "can't be dimmed". */
    @Test
    fun anUnknownDeviceIsNotCalledUndimmable() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp", capabilities = "power level")

        val answer = ask(proxy, "dim the garage light to 30 percent")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.explanation.get().contains("couldn't find"), answer.explanation.get())
    }
}
