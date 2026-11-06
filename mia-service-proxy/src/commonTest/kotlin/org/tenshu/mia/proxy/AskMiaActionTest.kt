package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.getOrNull
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.core.get
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDevice
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.askMia
import org.tenshu.mia.confirmMiaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MQ-D7: reads run, actions confirm.
 *
 * ⚠⚠ The property under test is that **asking never actuates**. A spoken interface has no undo
 * and no hover state, and a misheard slot ("turn off *everything*") is a bad night — so the
 * split between the turn that resolves and the tap that runs is the whole control.
 */
class AskMiaActionTest {

    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private suspend fun confirm(proxy: TestProxy, token: String): MiaAnswer {
        val result = proxy.miaService().confirmMiaAction(token)
        if (result is TResult.Failure<*>) error("confirm failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun TestProxy.seedDevice(id: String, name: String, room: String = "Kitchen") {
        executionContext.saveData(
            HomeDevice.create(
                context = tContext,
                deviceId = id,
                bridgeId = "bridge",
                kind = "light",
                name = name,
                room = room,
                joinedAtEpochMs = 1L,
            )
        )
    }

    /**
     * The `DeviceCommand` rows that exist — i.e. what actually actuated. Read through the
     * service's own relation rather than by probing DataIds, so the assertion goes down the
     * same path the hub does.
     */
    private suspend fun TestProxy.commands(): List<DeviceCommand> =
        environment().getService<HomeDeviceService>(HomeDeviceService.ID)
            .deviceCommands
            .getAll()
            .getOrNull()
            ?.toList()
            .orEmpty()
            .filterIsInstance<DeviceCommand>()

    @Test
    fun askingToTurnSomethingOffResolvesItButChangesNothing() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")

        val answer = ask(proxy, "turn off the kitchen light")

        assertEquals(MiaAnswer.VERDICT_CONFIRM, answer.verdict.get())
        val action = assertNotNull(answer.action)
        // ⚠⚠ The cell names the RESOLVED device, not the fragment typed.
        assertEquals("Turn off Kitchen light", action.title.get())
        assertEquals("Kitchen", action.detail.get())
        // ⚠⚠ And nothing has happened.
        assertTrue(proxy.commands().isEmpty(), "asking must not actuate")
    }

    @Test
    fun theTapIssuesTheCommand() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")
        val token = assertNotNull(ask(proxy, "turn off the kitchen light").action).token.get()

        val ack = confirm(proxy, token)

        assertEquals(MiaAnswer.VERDICT_ANSWERED, ack.verdict.get())
        assertTrue(ack.explanation.get().contains("Kitchen light"), ack.explanation.get())
        val command = proxy.commands().single()
        assertEquals("d1", command.targetDeviceId.get())
        // ⚠ setPower, never toggle — an utterance states what the user WANTS, and a flip is
        // relative to a reading that may already be stale.
        assertEquals(DeviceCommand.VERB_SET_POWER, command.verb.get())
        assertEquals(DeviceCommand.ARG_OFF, command.argValue.get())
    }

    @Test
    fun turnOnIsTheOppositeAbsoluteIntent() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")
        val token = assertNotNull(ask(proxy, "turn on the kitchen light").action).token.get()

        confirm(proxy, token)

        assertEquals(DeviceCommand.ARG_ON, proxy.commands().single().argValue.get())
    }

    @Test
    fun aTokenIsSingleUse() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")
        val token = assertNotNull(ask(proxy, "turn off the kitchen light").action).token.get()

        confirm(proxy, token)
        val second = confirm(proxy, token)

        assertTrue(second.explanation.get().contains("no longer"), second.explanation.get())
        assertEquals(1, proxy.commands().size, "re-tapping must not actuate twice")
    }

    @Test
    fun anUnknownTokenIsRefusedRatherThanIgnored() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val answer = confirm(proxy, "not-a-real-token")

        assertTrue(answer.explanation.get().contains("no longer"), answer.explanation.get())
        assertTrue(proxy.commands().isEmpty())
    }

    @Test
    fun twoMatchingDevicesAreNeverGuessedBetween() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen lamp")
        proxy.seedDevice("d2", "Bedroom lamp", room = "Bedroom")

        val answer = ask(proxy, "turn off the lamp")

        // ⚠⚠ Exactly where a keyword router would pick one and be confidently wrong — and
        // here the cost is physical.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("Kitchen lamp"), answer.explanation.get())
        assertTrue(answer.explanation.get().contains("Bedroom lamp"), answer.explanation.get())
    }

    @Test
    fun anUnknownDeviceSaysSo() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val answer = ask(proxy, "turn off the kitchen light")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }

    @Test
    fun askingAboutADeviceIsStillAQuestion() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")

        // ⚠⚠ "is the kitchen light on" contains "on". Reading that as a command and switching
        // the light on would be the worst possible misreading of a question.
        val answer = ask(proxy, "is the kitchen light on")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertNull(answer.action)
        assertEquals(1, answer.hits.toList().size)
        assertTrue(proxy.commands().isEmpty())
    }

    @Test
    fun toggleIsRefusedWithTheReason() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")

        val answer = ask(proxy, "toggle the kitchen light")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.explanation.get().contains("guess"), answer.explanation.get())
    }

    @Test
    fun aRevokedDeviceGrantStopsTheActionBeforeItIsEvenResolved() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light")
        proxy.setDeviceGrant(granted = false)

        val answer = ask(proxy, "turn off the kitchen light")

        // MQ-D6 and MQ-D7 compose: no grant, no resolution, nothing to tap.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }
}
