package org.tenshu.core.common.proxy

import org.tenshu.core.Data

/**
 * The connection for the proxy's own **system session** — the one `ExecutionContext` that belongs
 * to no client (`drive-upload-progress.md` P7). Every method is a no-op because there is nobody on
 * the other end: the system session exists so proxy-owned subsystems have a context that outlives
 * every app, not so anything can be pushed to a client.
 *
 * ⚠ [send] silently discards. That is correct here and would be a bug anywhere else: a stream
 * registered on the system context has no observer, so delivering its elements is meaningless. If a
 * subsystem ever *does* need to reach a client, it must do so through that client's own session —
 * routing it here would look like it worked and deliver nothing.
 */
internal class SystemClientConnection : ClientConnection {

    override fun init() = Unit

    override fun send(data: Data) = Unit

    override fun destroy() = Unit
}
