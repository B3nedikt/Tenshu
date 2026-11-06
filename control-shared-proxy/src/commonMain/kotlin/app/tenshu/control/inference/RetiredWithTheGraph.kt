package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A graph's Models repository, for its teardown (`ios-on-device-inference.md` §14.18.14): the
 * repository is built when a screen first asks for it, and a teardown must not build it to
 * retire it.
 *
 * ⚠⚠ **Latched.** A repository that is first built during or after the teardown is retired as
 * it is handed out: without the latch it would belong to a retired graph and never be told.
 * Whichever of [built] and [retire] runs second retires it; both may, which changes nothing.
 */
class RetiredWithTheGraph {

    private data class State(val repository: OnDeviceModelsRepository? = null, val retired: Boolean = false)

    private val state = MutableStateFlow(State())

    /** [repository], retired already if the graph was. */
    fun built(repository: OnDeviceModelsRepository): OnDeviceModelsRepository {
        state.update { it.copy(repository = repository) }
        if (state.value.retired) repository.retire()
        return repository
    }

    /** The graph was torn down. ⚠ Waits for nothing: see [OnDeviceModelsRepository.retire]. */
    fun retire() {
        state.update { it.copy(retired = true) }
        state.value.repository?.retire()
    }
}
