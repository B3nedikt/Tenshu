package app.tenshu.fit.ui.screens.record

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The RC-1 deep-link seam (fit-app.md §13.1): a platform shell asks the app to surface the
 * in-flight recording — Android's `MainActivity` raises it when the ongoing-recording
 * notification (or its Stop action's review notification) is tapped, and the shared shell
 * navigates to the live screen (Active) or the review screen (Reviewing) and [consume]s it.
 *
 * A latched flag rather than an event flow so a cold start works: the shell may not be composed
 * yet when the launch intent arrives; the request waits until it is.
 */
object RecordSurfaceRequests {

    private val _pending = MutableStateFlow(false)

    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun requestOpen() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
