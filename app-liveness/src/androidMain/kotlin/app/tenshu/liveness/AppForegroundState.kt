package app.tenshu.liveness

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide foregroundedness as a [StateFlow] — the [armForegroundService] retry signal.
 *
 * `ProcessLifecycle`'s ON_START / ON_STOP fire once for the whole app rather than per activity, and
 * are not re-fired by a configuration-change recreate, so this reports "is any of this app on
 * screen" without flapping. That is the right granularity: an FGS-start allowance belongs to the
 * process, not to one activity.
 *
 * One instance per process, created in `Application.onCreate`:
 *
 * ```
 * val foreground = AppForegroundState.observeProcess()
 * ```
 */
class AppForegroundState internal constructor() : DefaultLifecycleObserver {

    private val _isForeground = MutableStateFlow(false)

    /** `true` while any of this app's activities is started. */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }

    companion object {
        /** Creates the state and binds it to `ProcessLifecycleOwner`. Call once, from `onCreate`. */
        fun observeProcess(): AppForegroundState = AppForegroundState().also {
            ProcessLifecycleOwner.get().lifecycle.addObserver(it)
        }
    }
}
