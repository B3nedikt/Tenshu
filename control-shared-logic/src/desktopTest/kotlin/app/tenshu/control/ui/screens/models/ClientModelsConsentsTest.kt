package app.tenshu.control.ui.screens.models

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * ⚠⚠ The consent half off the caller's thread (`ios-on-device-inference.md` §14.18.15): Photos'
 * client is in-process and its executor switches no dispatcher, so a preference call would run on
 * the thread that made it, the main thread on iOS. What runs where is read at the one call each
 * path makes into what it was given: this device's id.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
class ClientModelsConsentsTest {

    /** ⚠ Never asked: each case below ends before a preference is read or written. */
    private val client = object : TenshuClient {
        override val environment: TenshuEnvironment get() = error("the client was asked")
        override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override fun <T : org.tenshu.core.Service> getService(id: String): T = error("the client was asked")
        override fun init() = Unit
        override fun destroy() = Unit
    }

    private val askedOn = mutableListOf<CoroutineDispatcher?>()

    private val devices = object : ModelsDevices {
        override suspend fun deviceId(): String {
            askedOn += currentCoroutineContext()[CoroutineDispatcher]
            return ""
        }

        override val roster: Flow<List<RosterDevice>?> = flowOf(null)
    }

    @Test
    fun everyPathRunsOnTheWorkItWasGivenAndNotOnTheCallers() = runTest {
        val work = StandardTestDispatcher(testScheduler, name = "work")
        val consents = ClientModelsConsents(client, devices, OnDeviceModelsCapabilities.features, work = work)

        assertFalse(consents.setDeviceConsent(OnDeviceModelsCapabilities.features.first(), true), "written under no id")
        assertFalse(consents.thisDeviceKnown().first())
        assertFalse(consents.clearOtherDeviceConsent(OnDeviceModelsCapabilities.features.first(), ""))
        consents.deviceIdOrPlaceholder()

        assertEquals(List<CoroutineDispatcher?>(askedOn.size) { work }, askedOn, "a consent call ran on the caller's thread")
        assertEquals(3, askedOn.size)
    }

    @Test
    fun controlsOwnClientStaysOnTheCallersThreadAsItWas() = runTest {
        val caller: CoroutineDispatcher? = currentCoroutineContext()[CoroutineDispatcher]
        val consents = ClientModelsConsents(client, devices, OnDeviceModelsCapabilities.features)

        consents.setDeviceConsent(OnDeviceModelsCapabilities.features.first(), true)

        assertEquals(listOf(caller), askedOn)
    }

    /**
     * The pause row follows the device id: a screen opened before the id was known, then a press
     * written under the real id, must read *paused* — a read pinned to the placeholder would say
     * *not paused* for good and offer no *Resume*. ⚠ Android and desktop read through it too.
     */
    @Test
    fun thePauseReadFollowsTheDeviceIdFromThePlaceholderToTheRealOne() = runTest {
        var id = ""
        val late = object : ModelsDevices {
            override suspend fun deviceId(): String = id
            override val roster: Flow<List<RosterDevice>?> = flowOf(null)
        }
        val consents = ClientModelsConsents(client, late, OnDeviceModelsCapabilities.features)
        val rows = mapOf("local-device" to MutableStateFlow(false), "phone-1" to MutableStateFlow(true))
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch {
            pausedOver(consents.deviceIdsOrPlaceholder(), read = { rows.getValue(it) }, readOnce = { rows.getValue(it).value }).collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf(false), seen, "the placeholder's row first")

        id = "phone-1"
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(false, true), seen, "the read stayed on the placeholder's row after the real id appeared")
    }
}
