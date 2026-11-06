package org.tenshu.core.common

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.StreamElement
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.core.TContext

/**
 * A remote client's request + push consumption wired over one [TaskTransport]: the
 * [RemoteBinaryTaskExecutor] issues ops (`transport.call`) and the [DefaultInteractorProvider] both
 * resolves interactors for the DSL *and* dispatches proxy-pushed stream elements (it is the
 * [org.tenshu.core.StreamElementDispatcher]). The executor's `streamElementHandler` is bound to the
 * provider so a `transport.onCallback` push — parsed into a [StreamElement] — is routed by streamId
 * to the right observer `Flow`.
 */
class RemoteConsumeStack internal constructor(
    val executor: RemoteBinaryTaskExecutor,
    val interactorProvider: DefaultInteractorProvider,
)

/**
 * Assemble a [RemoteConsumeStack] over [transport] — the Koin-free counterpart of the wiring
 * `coreCommonModule`'s `DefaultInteractorProvider` single does (and the Android/desktop
 * `PlatformClientModule`s inline). For client surfaces without the app DI graph — the iOS EPC client
 * (over `EpcRendezvousChannel`) and tests — so proxy-pushed observer/stream updates reach the app.
 *
 * [scope] hosts the callback-handling coroutine the executor launches per push (a transport like the
 * EPC channel is already connected, so there is no `bind()` to lazily create one). Tie it to the
 * client's lifetime — cancelling it stops consuming pushes.
 */
fun assembleRemoteConsumeStack(
    transport: TaskTransport,
    serializer: BinarySerializer,
    parser: BinaryParser,
    context: TContext,
    scope: CoroutineScope,
): RemoteConsumeStack {
    val executor = RemoteBinaryTaskExecutor(
        serializer = serializer,
        parser = parser,
        transport = transport,
        context = context,
        externalScope = scope,
    )
    val provider = DefaultInteractorProvider(taskExecutorProvider = { executor })
    executor.streamElementHandler = { element ->
        if (element is StreamElement<*>) provider.dispatchElement(element)
    }
    return RemoteConsumeStack(executor, provider)
}
