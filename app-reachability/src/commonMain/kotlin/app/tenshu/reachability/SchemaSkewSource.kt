package app.tenshu.reachability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.app.SchemaAnnouncer
import org.tenshu.core.common.app.SchemaSkew

/**
 * The one place the announce verdict becomes a reachability reason
 * (`core-schema-skew-surface.md` SK-1).
 *
 * ⚠⚠ **Exactly one definition, deliberately.** The direction → target mapping is a total function
 * over three cases, which is precisely the kind of thing that gets copied into each app's DI and
 * then drifts — leaving two apps telling the user to update different things for the same skew.
 * The alternative (an adapter per app module) was rejected for that reason; the `:core-common`
 * dependency this costs is `implementation`-scoped and does not reach consumers.
 */
fun SchemaSkew.toUnreachableReason(): UnreachableReason.Skew = UnreachableReason.Skew(
    update = when (direction) {
        // ⚠⚠ The inversion. The *client* being ahead means the **proxy** is the stale one, so the
        // user must update Control — not the app that is showing the banner.
        SchemaSkew.Direction.PROXY_BEHIND -> UpdateTarget.CONTROL
        SchemaSkew.Direction.CLIENT_BEHIND -> UpdateTarget.THIS_APP
        SchemaSkew.Direction.BOTH -> UpdateTarget.BOTH
    },
    serviceIds = serviceIds,
)

/**
 * A settable skew source for wiring sites that build the reachability engine **before** the
 * announcer exists — the common shape in DI graphs, where the client (and therefore the
 * announcer) is constructed lazily from a different module than the repository.
 *
 * ⚠⚠ Without this seam the wiring silently degrades to "no skew source", which compiles, runs,
 * and reports every skewed device as a transport outage — i.e. it looks exactly like the bug it
 * was added to fix. [flow] is valid from construction and starts carrying verdicts whenever
 * [bindTo] is called, so neither construction order can drop one.
 */
class MutableSchemaSkewSource {
    private val _flow = MutableStateFlow<UnreachableReason.Skew?>(null)
    val flow: StateFlow<UnreachableReason.Skew?> = _flow

    /**
     * Mirror a verdict flow into [flow] for as long as [scope] lives.
     *
     * ⚠ Takes the flow rather than the [SchemaAnnouncer] so the seam is reachable from a test —
     * an announcer needs a live client to construct, and a seam that can only be exercised in
     * production is how wiring ships inert.
     */
    fun bindTo(skew: StateFlow<SchemaSkew?>, scope: CoroutineScope) {
        scope.launch {
            skew.collect { _flow.value = it?.toUnreachableReason() }
        }
    }

    /** The production call site's ergonomics; [bindTo] is the seam it delegates to. */
    fun bind(announcer: SchemaAnnouncer, scope: CoroutineScope) = bindTo(announcer.skew, scope)

    // MARK: - the fence half (SK-3)

    private val _fence = MutableStateFlow<List<String>>(emptyList())

    /**
     * The services this device is behind the realm on — empty when in step
     * (`core-schema-skew-surface.md` SK-3). Fed to `ControlReachability.schemaFence`.
     *
     * ⚠⚠ Held on the **same** object as the announce verdict on purpose. Both are "this app's
     * schema is out of step with something", both are read by the same banner, and both are
     * written from the client module while the repository that reads them may already exist.
     * Two singles would be two chances to bind one and forget the other, and a forgotten bind
     * here is invisible — the app simply never mentions the problem.
     */
    val fence: StateFlow<List<String>> = _fence

    /**
     * Mirror the proxy's fence verdict into [fence] for as long as [scope] lives.
     *
     * ⚠ Takes the already-projected service-id list rather than `SyncService`, for the same
     * reason [bindTo] takes a flow: `:app-reachability` must not grow a sync dependency, and a
     * seam that can only be exercised in production is how wiring ships inert.
     */
    fun bindFence(fencedServices: Flow<List<String>>, scope: CoroutineScope) {
        scope.launch {
            fencedServices.collect { _fence.value = it }
        }
    }

    // MARK: - the host half (SK-2)

    private val _host = MutableStateFlow<ProxyHost?>(null)

    /**
     * Which binary owns the proxy — `null` until a `RuntimeIdentity` row is read, and `null`
     * forever on a host that publishes none.
     *
     * ⚠⚠ `null` must not be collapsed to a default. It means *"nobody said"*, and the banner's job
     * then is to report the device is behind without naming an app. Defaulting to `CONTROL` would
     * tell a route-1 user to update Control; defaulting to `THIS_APP` would tell an Android user
     * to update the app they are in. Both are wrong on one platform, which is the whole reason
     * this row exists.
     */
    val host: StateFlow<ProxyHost?> = _host

    /** Mirror the published runtime identity for as long as [scope] lives. */
    fun bindHost(isControlHosted: Flow<Boolean?>, scope: CoroutineScope) {
        scope.launch {
            isControlHosted.collect { _host.value = ProxyHost.fromIsControlHosted(it) }
        }
    }
}
