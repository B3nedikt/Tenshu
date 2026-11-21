package app.tenshu.mia.ui.screens.settings

import app.tenshu.mia.data.MiaGrantsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.mia.MiaSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class GrantsViewModelTest {

    private class FakeGrants(initial: Set<MiaSource>) : MiaGrantsRepository {
        val state = MutableStateFlow(initial)
        var writes = 0
        override fun granted(): Flow<Set<MiaSource>> = state
        override suspend fun setGranted(source: MiaSource, granted: Boolean) {
            writes++
            state.value = if (granted) state.value + source else state.value - source
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun withNoRepositoryEverySourceReadsAsOnAndTheSwitchesAreDisabled() = vmTest { vms ->
        val viewModel = vms.own(GrantsViewModel(repository = null))

        // ⚠ Truthful rather than convenient: with no proxy there is nothing to grant OR
        // revoke, so showing them off would suggest Mia had been restricted when it has not.
        assertEquals(MiaSource.entries.toSet(), viewModel.uiState.value.granted)
        assertFalse(viewModel.uiState.value.editable)
    }

    @Test
    fun aDisabledSwitchWritesNothing() = vmTest { vms ->
        val viewModel = vms.own(GrantsViewModel(repository = null))
        viewModel.setGranted(MiaSource.TIMELINE, granted = false)
        assertEquals(MiaSource.entries.toSet(), viewModel.uiState.value.granted)
    }

    @Test
    fun revokingASourceWritesItAndTheStateFollows() = vmTest { vms ->
        val repository = FakeGrants(MiaSource.entries.toSet())
        val viewModel = vms.own(GrantsViewModel(repository))

        viewModel.setGranted(MiaSource.TIMELINE, granted = false)

        assertEquals(1, repository.writes)
        assertFalse(MiaSource.TIMELINE in viewModel.uiState.value.granted)
        assertTrue(MiaSource.FILES in viewModel.uiState.value.granted)
        assertTrue(viewModel.uiState.value.editable)
    }

    @Test
    fun theStateFollowsAChangeMadeElsewhere() = vmTest { vms ->
        // A realm-scoped grant is revocable from any device, so the switch has to follow the
        // row rather than only its own taps.
        val repository = FakeGrants(MiaSource.entries.toSet())
        val viewModel = vms.own(GrantsViewModel(repository))

        repository.state.value = MiaSource.entries.toSet() - MiaSource.PLACES

        assertFalse(MiaSource.PLACES in viewModel.uiState.value.granted)
    }
}
