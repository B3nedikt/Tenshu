package org.tenshu.core

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Whether this client's channel to its proxy is currently up
 * (`docs/proposals/core-client-resubscription.md` R4/R5).
 *
 * `TenshuClient.proxyConnected` is the same fact shaped for a *consumer* — a `StateFlow` an app's
 * UI collects. This is the same fact shaped for the *machinery* in `:core`, which sits below the
 * transport and cannot see a `TenshuClient`: a level read plus a way to sleep until the channel
 * comes back, so a retry loop can wait on the reconnect **edge** instead of a timer.
 *
 * ⚠ **Connected is not the same as usable, and only this direction is reliable.** A disconnected
 * channel cannot serve a read — that inference is sound, and it is the only one made here. The
 * converse is false: a *connected* proxy still refuses every call when the two sides disagree on a
 * schema version (the announce gate's `UnannouncedSessionError`), which is why nothing treats
 * `isConnected == true` as evidence a read will succeed. Retries fall back to their timer in that
 * state, because a refusal has no edge to wait for.
 *
 * Optional, like [BackgroundScopeProvider]: absent from a [TContext] means "no channel to lose"
 * (in-process proxies, tests), and callers read that as permanently connected — accurate rather
 * than a fallback.
 */
interface ProxyConnectivity : TComponent {

    /** Whether the channel is up right now. */
    val isConnected: Boolean

    /**
     * Suspends until the channel is back, returning at once when it already is.
     *
     * ⚠ Implementations must be **edge-correct against a concurrent reconnect**: a reconnect that
     * lands between a caller's [isConnected] read and its call here must not be missed, or the
     * caller sleeps until the *next* one — which, for a proxy that stays up, is never.
     */
    suspend fun awaitConnected()

    companion object {
        const val ID = "core/proxy-connectivity"
    }
}

/**
 * Accessor for the [ProxyConnectivity] from [TContext]. `null` where there is no channel to lose.
 */
fun TContext.proxyConnectivity(): ProxyConnectivity? =
    components[ProxyConnectivity.ID] as? ProxyConnectivity
