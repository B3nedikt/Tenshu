package org.tenshu.core

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Marks every IO call issued inside [block] as a **cancellable read**: when the caller is cancelled
 * while the call is in flight, the proxy stops evaluating it too, instead of walking it to the end
 * and answering nobody (`docs/proposals/core-background-processing.md` §6.2.1).
 *
 * ⚠⚠ **Reads only.** A plain IO call is *not* cancelled in the proxy when its caller is — on every
 * transport and in-process alike — and code relies on that: a save launched from a screen that
 * closes a moment later still lands. Inside this block that guarantee is gone for every call, so a
 * write issued here may stop part-way. Wrap the read, not the screen.
 *
 * Without the block a call behaves exactly as before. A call inside a `withTask` block keeps its task
 * semantics (the task's id, its sync lane, `TaskHandle.cancel()`); this element is ignored there.
 *
 * Against a Control too old to stop a read (`docs/protocol/desktop-ipc.md` §2.3; the Android
 * `ipcRevision()` probe) the call is an ordinary plain call: the caller still unwinds as it always
 * did, the proxy simply finishes the walk.
 */
suspend fun <T> cancellableReads(block: suspend () -> T): T =
    withContext(CancellableReadsElement) { block() }

/** The coroutine-context element [cancellableReads] installs; read by the interactor layer. */
object CancellableReadsElement : CoroutineContext.Element, CoroutineContext.Key<CancellableReadsElement> {
    override val key: CoroutineContext.Key<*> get() = this
}
