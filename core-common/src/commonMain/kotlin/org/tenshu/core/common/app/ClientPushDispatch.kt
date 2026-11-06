package org.tenshu.core.common.app

import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor

/**
 * Route everything the proxy pushes at this client (`control-cross-app-open.md` TT-1b).
 *
 * ⚠⚠ **Until TT-1b there was exactly one kind of push, and every app's DI said so**: nine copies of
 * `if (element is StreamElement<*>) dispatch(it)`, each silently dropping anything else. A cross-app
 * open is the first *unsolicited* composite — it answers no pending task and belongs to no stream —
 * so it fell through all nine without a trace. This exists so the answer to "what else can arrive"
 * lives in one place rather than being re-decided per app.
 *
 * ⚠ [openTargets] resolves to null for an app that handles no data types. Such an app still gets
 * its stream elements, and a request it was never meant to receive is ignored rather than crashing
 * it — the handler table is Control's business (CO-D2), and a client is not the place to re-litigate
 * it.
 *
 * ⚠⚠ **Both parameters are PROVIDERS, and that is load-bearing rather than stylistic.** The
 * dispatcher is `DefaultInteractorProvider`, which is built from the `TaskExecutor` — so resolving
 * it while constructing that same executor closes a DI cycle and the app dies at launch with a
 * `StackOverflowError` inside Koin, thousands of frames from anything recognisable. The nine
 * hand-written copies this replaced were safe only because their `get()` sat *inside* the handler
 * lambda, where it runs long after the graph is built. Taking instances here would have quietly
 * moved that call to construction time for every adopter.
 */
fun RemoteBinaryTaskExecutor.wireClientPushDispatch(
    streams: () -> StreamElementDispatcher,
    openTargets: () -> OpenTargetInbox? = { null },
) {
    streamElementHandler = { pushed: Data ->
        when {
            pushed is StreamElement<*> -> streams().dispatchElement(pushed)
            pushed is OpenTargetRequest -> openTargets()?.deliver(pushed.target) ?: Unit
            // ⚠ Deliberately silent. An app that receives a push it has no binding for is a
            // routing question, and one it cannot answer — logging per-element here would put a
            // line in every app's log for every stream emission a future type adds.
            else -> Unit
        }
    }
}
