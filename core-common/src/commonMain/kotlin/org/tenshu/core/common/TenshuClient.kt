@file:JvmName("TenshuClientCommon")

package org.tenshu.core.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.tenshu.core.Service
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.CallOutcome
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.TContext
import kotlin.jvm.JvmName

interface TenshuClient {
    val environment: TenshuEnvironment

    /**
     * Whether this client can currently reach its proxy
     * (`docs/proposals/core-client-resubscription.md` R4).
     *
     * ⚠ **Client-local, and that is the entire point.** Every other signal an app judges its state
     * by — storage, sync health, the schema fence — is a row it *reads from the proxy*, so during
     * an outage they are all absent and the app cannot distinguish "nothing to show" from "cannot
     * ask". Drive rendered an empty file list with no banner at all in exactly that state.
     *
     * Always `true` where client and proxy share a process (iOS route-1, the web runtime, every
     * in-process test), which is accurate rather than a fallback: there is no channel to lose.
     */
    val proxyConnected: StateFlow<Boolean>

    /**
     * Whether **Control is installed on this device** — `true`/`false`, or `null` for *cannot
     * tell* (`ADR 0025`). The companion to [proxyConnected]: that one says the channel is down,
     * this one says whether there is anything at the other end to wake up.
     *
     * ⚠⚠ **Only meaningful while [proxyConnected] is `false`**, and only as a source of *words*.
     * A reachable proxy proves Control is there far better than any query, and an app that gates
     * behaviour rather than copy on this has confused "which sentence do I show" with "what am I
     * allowed to do".
     *
     * ⚠ `null` where client and proxy share a process (iOS route-1, the web runtime, tests):
     * there is no channel to lose and no separate Control binary to look for, so the question does
     * not arise. iOS route-1 apps learn Control's absence from ADR 0022's `openURL` attempt
     * instead — the authoritative signal on a platform that will not answer a query.
     */
    suspend fun controlInstalled(): Boolean? = null

    /**
     * Whether the proxy last answered with something this build **could not read** — schema skew,
     * not an outage (`CallOutcome.Unintelligible`).
     *
     * ⚠⚠ The distinction [proxyConnected] cannot make. A channel can be perfectly up while the two
     * sides disagree about a type, and a repository catching the resulting failure has no way to
     * tell that from a dead proxy — so it reported an outage, and the user was told to check a
     * connection that was working instead of to update.
     *
     * ⚠⚠ **Named for what was observed, not for what it means.** An unreadable answer is strong
     * evidence of schema skew and that is what `ControlReachability` turns it into — but it is not
     * proof: a corrupt frame or a serializer bug looks identical from here. The inference belongs
     * to the consumer, alongside the announce's verdict, which *is* authoritative because it
     * compared two version vectors and knows which side is behind. Calling this
     * `proxySchemaSkew` would put a conclusion in `:core-common` that `:core-common` cannot reach,
     * and would read as the equal of a verdict it is strictly weaker than.
     *
     * ⚠ Latches on an unreadable answer and clears on the next readable one. It is a *condition*,
     * not an event: whatever causes it persists until something changes, and one good response is
     * proof that it is over.
     *
     * ⚠ Always `false` where client and proxy share a process (iOS route-1, the web runtime, every
     * in-process test) — one process cannot disagree with itself about a schema.
     */
    val proxyAnswerUnreadable: StateFlow<Boolean> get() = MutableStateFlow(false)

    /**
     * One event per call the proxy **answered successfully** (`CallOutcome.Answered`).
     *
     * ⚠⚠ **An event, not a level, and deliberately not an answer.** It is evidence worth
     * re-checking on, never a verdict: `ControlReachability` uses it to *wake its probe*, and the
     * probe decides. Setting the state from here instead would be strictly worse than the
     * hand-written `markReachable` calls it replaces — those sat on a read's success arm, whereas
     * a signal that fires per *call* has to be trusted about reads it never saw.
     *
     * ⚠ The value carries nothing; only the emissions do. Conflated, so a burst of calls is one
     * wake rather than a queue of them.
     *
     * ⚠ Silent where client and proxy share a process (iOS route-1, the web runtime, every
     * in-process test): no round trip is classified, and there is no channel to lose either, so
     * the probe's own answer is the whole story there.
     */
    val proxyAnswered: Flow<Unit> get() = emptyFlow()

    fun <T : Service> getService(id: String): T

    fun init()
    fun destroy()
}

class DefaultTenshuClient(
    override val environment: TenshuEnvironment
) : TenshuClient {

    // Optimistic until told otherwise: a remote executor reports its real state as soon as the
    // callback is registered below, and an in-process one never disconnects.
    private val connected = MutableStateFlow(true)

    override val proxyConnected: StateFlow<Boolean> = connected.asStateFlow()

    private val unreadable = MutableStateFlow(false)

    override val proxyAnswerUnreadable: StateFlow<Boolean> = unreadable.asStateFlow()

    private val answered = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val proxyAnswered: Flow<Unit> = answered.asSharedFlow()

    override suspend fun controlInstalled(): Boolean? =
        (environment.taskExecutor as? RemoteTaskExecutor)?.controlInstalled()

    override fun <T : Service> getService(id: String): T = environment.getService(id)

    override fun init() {
        val executor = environment.taskExecutor
        if (executor is RemoteTaskExecutor) {
            executor.setOnConnectionChanged { connected.value = it }
            executor.setOnCallOutcome { outcome ->
                when (outcome) {
                    // A readable answer ends the skew condition — that is the proof an update
                    // landed — and a *successful* one is additionally worth re-probing on.
                    CallOutcome.Answered -> {
                        unreadable.value = false
                        answered.tryEmit(Unit)
                    }

                    CallOutcome.AnsweredWithError -> unreadable.value = false
                    CallOutcome.Unintelligible -> unreadable.value = true

                    // ⚠ Leaves the latch alone rather than clearing it. A dead channel is not a
                    // readable answer, and treating it as one discards a skew verdict the
                    // reconnect then has to re-earn — during which the banner tells the user to
                    // check a connection instead of to update.
                    CallOutcome.NotConnected -> Unit
                }
            }
            executor.bind()
        }
    }

    override fun destroy() {
        val executor = environment.taskExecutor
        if (executor is RemoteTaskExecutor) {
            executor.setOnConnectionChanged(null)
            executor.setOnCallOutcome(null)
            executor.unbind()
        }
    }
}

fun createRemoteTenshuEnvironment(
    tContext: TContext,
    schemaRegistry: SchemaRegistry,
    remoteTaskExecutor: RemoteTaskExecutor
): TenshuEnvironment {

    return TenshuEnvironment(
        taskExecutor = remoteTaskExecutor,
        schemaRegistry = schemaRegistry,
        tContext = tContext,
        requestChainFactory = { ctx ->
            TaskRequestChain(
                taskExecutor = remoteTaskExecutor,
                context = ctx
            )
        }
    )
}
