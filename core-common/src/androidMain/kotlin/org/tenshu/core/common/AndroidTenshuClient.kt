package org.tenshu.core.common

/**
 * ⚠ Forwards [init]/[destroy] to the delegate rather than re-implementing them.
 *
 * It used to bind the executor itself, which read as a harmless duplicate of what
 * [DefaultTenshuClient] already does — until the delegate's `init` also became where
 * `proxyConnected` is wired (`core-client-resubscription.md` R4). An override that skips the
 * delegate silently skips whatever the delegate's `init` grows, so on the one platform where the
 * proxy actually goes away the signal would have sat at a constant `true`.
 *
 * The old override cast to `RemoteBinaryTaskExecutor` where the delegate checks the
 * `RemoteTaskExecutor` interface it implements, so binding behaviour is unchanged.
 */
class AndroidTenshuClient(
    environment: TenshuEnvironment,
    private val delegate: DefaultTenshuClient = DefaultTenshuClient(environment),
) : TenshuClient by delegate {

    override fun init() = delegate.init()

    override fun destroy() = delegate.destroy()
}
