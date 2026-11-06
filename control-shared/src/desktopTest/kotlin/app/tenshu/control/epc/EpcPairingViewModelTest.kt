package app.tenshu.control.epc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class EpcPairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun surfaces_the_invite_then_the_sas() = vmTest(dispatcher) { vms ->
        val host = EpcPairingHost { onInvite, onSas ->
            onInvite("tenshu://epc/pair#abc")
            onSas("123456")
            awaitCancellation() // the real serve loop blocks until the screen is dismissed
        }
        val vm = vms.own(EpcPairingViewModel(host))

        vm.start()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(EpcPairingStatus.Connected, state.status)
        assertEquals("tenshu://epc/pair#abc", state.pairingUri)
        assertEquals("123456", state.sas)

        vm.stop()
    }

    @Test
    fun start_is_idempotent_while_serving() = vmTest(dispatcher) { vms ->
        var runs = 0
        val host = EpcPairingHost { onInvite, _ ->
            runs++
            onInvite("u")
            awaitCancellation()
        }
        val vm = vms.own(EpcPairingViewModel(host))

        vm.start()
        vm.start()
        advanceUntilIdle()

        assertEquals(1, runs)
        vm.stop()
    }

    @Test
    fun surfaces_an_error_when_the_host_fails() = vmTest(dispatcher) { vms ->
        val host = EpcPairingHost { _, _ -> throw RuntimeException("relay down") }
        val vm = vms.own(EpcPairingViewModel(host))

        vm.start()
        advanceUntilIdle()

        assertEquals(EpcPairingStatus.Error, vm.uiState.value.status)
        assertEquals("relay down", vm.uiState.value.error)
    }

    @Test
    fun stop_resets_to_idle_for_a_fresh_pairing() = vmTest(dispatcher) { vms ->
        val host = EpcPairingHost { onInvite, _ -> onInvite("u"); awaitCancellation() }
        val vm = vms.own(EpcPairingViewModel(host))

        vm.start()
        advanceUntilIdle()
        vm.stop()

        assertEquals(EpcPairingStatus.Idle, vm.uiState.value.status)
        assertEquals(null, vm.uiState.value.pairingUri)
    }
}
