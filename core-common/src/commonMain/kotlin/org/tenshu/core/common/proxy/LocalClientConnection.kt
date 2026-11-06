package org.tenshu.core.common.proxy

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tenshu.core.Data

/**
 * The in-process [ClientConnection]: what the proxy [send]s is handed to every registered listener
 * on the sending thread.
 *
 * ⚠⚠ Registration is **atomic with [destroy]**, and the connection remembers that it was
 * destroyed. Control adopts a session (`SessionAwareLocalTaskExecutor.onSessionAdopted`, which
 * installs `forwardStreamElementsInOrder`) on one thread while `quiesceSessionsAndJoin()` can be
 * tearing the same session down on another; with a plain list and no destroyed state, a listener or
 * hook added *after* `destroy()` had cleared them would simply stay — a consumer on a process-lifetime
 * scope that nothing would ever cancel, once per lost race. So: a listener added to a
 * destroyed connection is refused (returns `false`), and a destroy hook added to one runs at once.
 * The state is one immutable snapshot swapped by CAS — the same copy-on-write idiom as
 * `ClientRegistry` — which also makes [send]'s iteration safe against a concurrent registration.
 */
@OptIn(ExperimentalAtomicApi::class)
class LocalClientConnection : ClientConnection {

    private class State(
        val listeners: List<(Data) -> Unit>,
        val onDestroy: List<() -> Unit>,
        val destroyed: Boolean,
    )

    private val state = AtomicReference(State(emptyList(), emptyList(), destroyed = false))

    /** Registers [l] for every future [send]; `false`, and nothing registered, once [destroy]ed. */
    fun addListener(l: (Data) -> Unit): Boolean {
        while (true) {
            val current = state.load()
            if (current.destroyed) return false
            val next = State(current.listeners + l, current.onDestroy, destroyed = false)
            if (state.compareAndSet(current, next)) return true
        }
    }

    /**
     * Runs once, when [destroy] tears the connection down — after the listeners are dropped — or
     * **immediately** if it already has. What a listener that owns a resource (a channel, a consumer
     * coroutine) hooks its release on; see [forwardStreamElementsInOrder].
     */
    fun addOnDestroy(l: () -> Unit) {
        while (true) {
            val current = state.load()
            if (current.destroyed) {
                l()
                return
            }
            val next = State(current.listeners, current.onDestroy + l, destroyed = false)
            if (state.compareAndSet(current, next)) return
        }
    }

    override fun init() {}

    override fun send(data: Data) {
        state.load().listeners.forEach { it(data) }
    }

    override fun destroy() {
        val old = state.exchange(State(emptyList(), emptyList(), destroyed = true))
        old.onDestroy.forEach { it() }
    }
}
