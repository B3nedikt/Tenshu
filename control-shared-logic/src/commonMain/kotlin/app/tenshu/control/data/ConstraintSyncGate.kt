package app.tenshu.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.tenshu.preference.PreferenceService
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.common.SyncGate

/**
 * Path A (`bgproc-scheduling-roles.md`): the [SyncGate] implementation that
 * gates the in-process `SyncScheduler` on the realm's background-sync
 * preferences. Tracks the latest [ConstraintSpec] from
 * [backgroundSyncConstraints] and compares it against the current
 * [NetworkStateProvider] status each tick.
 *
 * Lives in `:control-shared` — the only module with both `:preference-service`
 * (the resolver) and the sync proxy wiring. The `SyncScheduler` itself stays
 * preference-agnostic; the binding happens here at the DI layer.
 *
 * [scope] should be the session's `ExecutionContext.sessionScope` so the
 * constraint collector is cancelled with the session rather than leaking onto
 * a detached dispatcher (see the "Proxy Background Lifecycle" tech-debt entry).
 */
class ConstraintSyncGate(
    preferenceService: PreferenceService,
    private val networkState: NetworkStateProvider,
    scope: CoroutineScope,
) : SyncGate {

    // Thread-safe: written by the collector, read on the scheduler tick.
    private val latest = MutableStateFlow<ConstraintSpec?>(null)

    init {
        preferenceService.backgroundSyncConstraints()
            .onEach { latest.value = it }
            // Defensive: during teardown the collector can briefly observe a
            // half-disposed context (e.g. a dropped Koin singleton) before
            // cancellation propagates. Swallow rather than let it escape onto
            // the dispatcher as an uncaught exception (which, on the test
            // dispatcher, cascades into unrelated tests via
            // `UncaughtExceptionsBeforeTest`). Quiescent teardown (Phase 2)
            // removes the window; this keeps it non-fatal until then.
            .catch {
                // ⚠ …except a cancellation, which is the scope closing normally and must stay one:
                // swallowed, this collector completes as a success and the teardown it is riding
                // has nothing to join on.
                if (it is CancellationException) throw it
                it.printStackTrace()
            }
            .launchIn(scope)
    }

    override suspend fun shouldSync(): Boolean =
        constraintAllowsSync(latest.value, networkState.current())
}

/**
 * Pure decision: does [constraint]'s network policy permit syncing on
 * [network]? A `null` constraint (policy not loaded yet) fails open — syncing
 * on any live connection — rather than blocking sync during startup.
 */
internal fun constraintAllowsSync(
    constraint: ConstraintSpec?,
    network: NetworkStatus,
): Boolean = when (constraint?.network?.get()) {
    ConstraintSpec.NETWORK_UNMETERED -> network == NetworkStatus.UNMETERED
    ConstraintSpec.NETWORK_NONE -> false
    // NETWORK_ANY, an unknown value, or no constraint yet → any live connection.
    else -> network != NetworkStatus.NONE
}
