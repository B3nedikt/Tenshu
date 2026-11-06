package app.tenshu.reachability

/**
 * *Why* the shell is showing a banner — the missing half of [ControlReachability.state]
 * (`core-schema-skew-surface.md` SK-1, extended by SK-2/SK-3).
 *
 * ⚠⚠ **The engine already knew these were different and reported them the same.** Its own class
 * doc says a connected proxy *"still refuses every read when the two sides disagree on a schema
 * version (the announce gate), which is the 'Can't reach Control' case that is not a transport
 * failure at all"* — and then folded it into the same `state = false` that a dead channel
 * produces. Both are genuinely unreachable; only one recovers on its own, and only one has an
 * action the user can take.
 *
 * ⚠ **The name is now narrower than the type**, and that is a deliberate trade. [Fenced] is not an
 * *unreachable* case at all — a fenced device reads perfectly well, it just stops receiving. The
 * honest name would be something like `ShellAlert`, but the rename would churn four apps that
 * shipped this type days earlier for no behavioural gain, so the name stays and this paragraph
 * carries the truth instead.
 *
 * ⚠ Deliberately declared here rather than reusing `core-common`'s `SchemaSkew`:
 * `:app-reachability` depends on coroutines and `:core-common` only for the announce adapter, and
 * the DI module that owns both does the wiring.
 */
sealed interface UnreachableReason {

    /**
     * The channel is down, or the proxy is not answering. **Recovery is automatic** — the probe
     * clears this by itself, which is why the banner for it carries no action.
     */
    data object Transport : UnreachableReason

    /**
     * **Control is not installed on this device** — a strict refinement of [Transport]: the same
     * dead channel, with the one cause the user can actually do something about.
     *
     * ⚠⚠ **This is the case ADR 0022 was written about, arriving on the other platforms.** Folded
     * into [Transport] it renders as *"open Control and unlock it, then come back"* — an
     * instruction naming an app that is not on the device. The words for this case must offer
     * installation, never launching.
     *
     * ⚠ **Absence has to be *known*, never inferred from silence.** The engine reports this only
     * when its `controlInstalled` probe answers `false`; a probe that answers `null` (cannot tell)
     * leaves the outage as [Transport], which is the honest degrade — see
     * [ControlReachability]'s `controlInstalled` parameter. Desktop answers `null` today, so this
     * case simply never fires there rather than guessing.
     */
    data object NotInstalled : UnreachableReason

    /**
     * **Control is installed and answering, and this device has no realm** — nothing has been
     * created or joined here yet (`ADR 0025` §Decision 8). Control's own `LandingState.FirstTime`,
     * seen from an app.
     *
     * ⚠⚠ **Nothing is unreachable, and that is why it had no surface.** The proxy opens its store
     * whether or not a realm exists, so every probe succeeds and every read returns *empty*: the
     * shell rendered "No photos yet" and "Your timeline starts here" over a library that does not
     * exist. Like [Fenced] it lives on the **reachable** branch; unlike [Fenced] it is permanent
     * until the user acts, so it takes the wall rather than a banner — the [NotInstalled] posture.
     *
     * ⚠ **Absence has to be *known*.** The engine reports this only when its `controlHasRealm`
     * probe answers `false` — a *successful* status read with no owner. A failed read, or an app
     * that cannot ask, answers `null` and leaves the shell exactly as it was.
     *
     * ⚠ A **locked** realm is never this case (maintainer's decision, 2026-10-03, §Decision 8.1):
     * it has a library, locked is temporary, and unlocking is its own existing surface's job.
     */
    data object NotSetUp : UnreachableReason

    /**
     * **Control is answering (or not yet known), and whether this device has a realm is not known
     * yet** — the realm verdict is PENDING (`ADR 0025` §Decision 8, §8.6). The iOS twin is
     * `ControlProvisioning.checking`.
     *
     * ⚠⚠ The roots render a **non-interactive** checking state for it — no shell, nothing that
     * writes — because the shell's own writes (an upload, a calendar save) are user acts the wall
     * otherwise covers, and in this window nobody knows yet whether there is a realm to write into.
     * With the realm read's retry backoff it normally lasts about a second; `AppControlCheckingGate`
     * keeps it invisible for the first 300 ms and turns it into a retry wall if it outlasts 20 s.
     *
     * Never reported while Control is unreachable (the transport reasons own that), and never by an
     * engine with no realm probe wired.
     */
    data object Checking : UnreachableReason

    /**
     * **Skew B, app ↔ proxy.** The proxy answered and *refused*: the two sides disagree on a
     * schema version, so this app's session is rejected and nothing loads. Recovery requires the
     * user to update something, and [update] says what.
     */
    data class Skew(
        val update: UpdateTarget,
        /** Diagnostic detail for an expander — never the headline. */
        val serviceIds: List<String> = emptyList(),
    ) : UnreachableReason

    /**
     * **Skew A, device ↔ realm.** This device's schema is behind the realm epoch for a service it
     * materialises, so the sync scheduler has stopped *pulling* rather than garble blocks it
     * cannot parse (`core-schema-evolution.md` §7.3).
     *
     * ⚠⚠ **Nothing is unreachable here, and that is why this case needed its own slice.** The
     * fence gates `pullAllowed` alone: local reads keep working, every write still pushes, and
     * `ControlReachability.state` stays `true` throughout. So the shell's original
     * `controlReachable == false` condition can *never* fire for this case — a device in it looks
     * completely healthy while silently receiving nothing. That is precisely the failure that had
     * no surface at all before SK-3.
     */
    data class Fenced(
        /**
         * ⚠⚠ **Nullable, and `null` is a real state rather than a default.** It means no host
         * published a [org.tenshu.sync.RuntimeIdentity] row, so we genuinely do not know which
         * binary owns this runtime. The banner must then say the device is behind *without*
         * naming an app — guessing sends the user to update something that may be current, and
         * "update the thing you are looking at" is wrong on Android and desktop specifically.
         */
        val update: UpdateTarget?,
        /** The services whose versions are behind the realm. Diagnostic detail, never the headline. */
        val serviceIds: List<String> = emptyList(),
    ) : UnreachableReason
}

/**
 * Which installable binary the user must update.
 *
 * ⚠⚠ **[THIS_APP] and [CONTROL] are not a platform distinction**, though they correlate with one.
 * For [UnreachableReason.Skew] they come from the *direction* of the version disagreement, which
 * the proxy computes by comparing two vectors it holds at once. For [UnreachableReason.Fenced]
 * there is no direction — a fenced device is always the one behind — so the answer comes from
 * [ProxyHost] instead: whoever owns the runtime is who has to be updated.
 */
enum class UpdateTarget {
    /** The app rendering the banner is the one to update. */
    THIS_APP,

    /** Tenshu Control is the one to update. */
    CONTROL,

    /** Divergent builds — neither alone is enough. */
    BOTH,
}

/**
 * Which binary owns the proxy this app talks to (`core-schema-skew-surface.md` §3.1: *a fence
 * belongs to a runtime, and a runtime is owned by exactly one installable binary*).
 *
 * ## ⚠⚠ How this is resolved — corrected in SK-2
 *
 * **The proxy stamps it**, and a client reads it: `RuntimeIdentity` is a non-`Syncable` row, one per
 * writer, published once at proxy init carrying the host binary's app id and version. This is §3.2's
 * original design, arrived at the long way round.
 *
 * An earlier SK-2 draft had the *wiring* declare it client-side, on the finding that "the proxy
 * does not know which binary it is". ⚠ That finding was wrong: `loggingServiceProxyImplementationFactory`
 * has taken `appId` + `appVersion` from every host all along, so threading the same into the sync
 * proxy follows an existing pattern rather than inventing one. A client-side declaration would
 * also have been an assertion about deployment topology made in the layer least able to keep it
 * true.
 *
 * ⚠⚠ What *was* right in that draft is narrower and still binding: the identity must **not** be a
 * field on `SchemaFenceStatus`. Without a version bump it garbles a version-skewed app↔Control
 * pair; with one, bumping `SyncService` pull-fences every not-yet-updated device in the realm over
 * a purely local row. A new composite escapes both — see `RuntimeIdentity`'s KDoc.
 */
enum class ProxyHost {
    /** Out of process — Android/desktop over Binder/UDS, and iOS EPC. Control owns the runtime. */
    CONTROL,

    /** In process — iOS route-1, where the app links its own proxy and is its own runtime owner. */
    THIS_APP,
    ;

    /** The binary to name when this device is fenced. */
    fun toUpdateTarget(): UpdateTarget = when (this) {
        CONTROL -> UpdateTarget.CONTROL
        THIS_APP -> UpdateTarget.THIS_APP
    }

    companion object {
        /**
         * Read a published runtime identity: `true` ⇒ Control hosts it, `false` ⇒ some other
         * binary does (route 1), `null` ⇒ nobody said, so neither may be claimed.
         */
        fun fromIsControlHosted(isControlHosted: Boolean?): ProxyHost? = when (isControlHosted) {
            true -> CONTROL
            false -> THIS_APP
            null -> null
        }
    }
}

/**
 * The reason of a repository with no reachability engine (a mock, a test, iOS route 1): a permanent
 * `null`. A `StateFlow`, like `ControlReachability.reason`, so an app root can read its first frame's
 * reason synchronously either way.
 */
val NO_UNREACHABLE_REASON: kotlinx.coroutines.flow.StateFlow<UnreachableReason?> =
    kotlinx.coroutines.flow.MutableStateFlow(null)
