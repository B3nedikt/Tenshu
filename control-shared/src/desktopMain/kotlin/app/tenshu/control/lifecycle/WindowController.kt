package app.tenshu.control.lifecycle

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decouples the Control window's visibility from the JVM process lifecycle
 * (docs/proposals/desktop-macos-integration.md §4 task 1, slice 1A).
 *
 * With the Swift tray as the persistent presence, the daemon (socket servers +
 * background loops) must outlive the Compose window. `Main.kt` always emits a
 * single `Window(visible = …)` bound to [visible] — never conditionally removed,
 * so `application { }` doesn't exit when the window is hidden — and toggles it
 * through this controller:
 *
 *  - [show] / [hide] flip [visible]. The bridge `openControlWindow` method calls
 *    [show]; the window's close button calls [hide] (close-to-tray).
 *  - [requestQuit] emits on [quitRequested]; `Main.kt` collects it and calls
 *    `exitApplication()`, after which the trailing `shutdownAndJoin()` quiesces
 *    the proxy. The bridge `quitApp` method (the tray's Quit) calls this.
 *
 * Thread-safe: the bridge invokes [show]/[requestQuit] off a bridge IO thread
 * while `application { }` observes [visible] on the EDT; `StateFlow` /
 * `SharedFlow` handle the hand-off.
 */
class WindowController(initiallyVisible: Boolean = true) {

    private val _visible = MutableStateFlow(initiallyVisible)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // replay = 0: a quit request is an event, not state. extraBufferCapacity = 1
    // so tryEmit from a non-suspending bridge thread never drops the signal.
    private val _quitRequested = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val quitRequested: SharedFlow<Unit> = _quitRequested.asSharedFlow()

    fun show() {
        _visible.value = true
    }

    fun hide() {
        _visible.value = false
    }

    fun requestQuit() {
        _quitRequested.tryEmit(Unit)
    }
}
