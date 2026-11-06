package app.tenshu.reachability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The **Control-reachability engine** (fit-app.md §9.14): tracks whether the app's
 * IPC reads/writes actually reach the Control proxy, and actively recovers when it returns.
 * The apps have no store of their own — every read goes over IPC — so an unreachable Control
 * (not installed / disabled / Control-desktop not running) folds every read to empty and the
 * app is silently blank; [state] is the signal that lets the shell say *why*.
 *
 * **The transport answers first, when it can.** [transportConnected] is `TenshuClient.proxyConnected`
 * (`core-client-resubscription.md` §4.3) — the client-local fact of whether the IPC channel is up.
 * It is **authoritative in one direction only**: `false` means unreachable *now*, with no probe
 * and no 5-second wait, because no call can cross a dead channel. `true` proves nothing — a
 * connected proxy still refuses every read when the two sides disagree on a schema version (the
 * announce gate), which is the "Can't reach Control" case that is not a transport failure at all.
 * So a reconnect is treated as a *reason to re-check*, never as an answer: it wakes the probe
 * immediately instead of waiting out the interval.
 *
 * **The probe owns detection in every other case.** Waiting on a read is no detector: a stream
 * that hangs is the silent blank screen this exists for. The client's subscribe retry
 * (`core-client-resubscription.md` §4.1) and the transport edge (§4.3) cover a dead channel; what
 * neither covers is a *connected* proxy refusing reads, which is exactly what the probe
 * detects. It runs at [probeIntervalMs] whenever [state] is anything but
 * known-good (`null` at launch, `false` while down), and is skipped while the transport reports
 * the channel down — a probe across a dead channel is a guaranteed-failing round trip.
 *
 * **Two things accelerate it, and neither answers for it.** [transportConnected]'s reconnect edge
 * and [answered] — one call the proxy actually served — both only *wake* the probe. A
 * [markReachable] from a read fold would be weaker evidence (a fold is not the executor's
 * classification of the round trip) and would skip the recovery below, because only the probe's
 * own flip runs that.
 *
 * **Recovery is active, not passive.** On a `false → true` probe flip the engine bumps
 * [readEpoch] — every read flow wrapped in [resubscribing] re-subscribes, so hung/dead streams
 * recover and live screens repopulate **without re-entry** — and runs [onRecovered] (e.g. Fit
 * drains its recorder outbox: Control being back is exactly when journalled saves can land).
 *
 * **The reason is separate from the fact.** [state] answers *whether* Control can serve reads;
 * [reason] answers *why not*, because the cases need different words and only one of them
 * recovers on its own. Supply [schemaSkew] to distinguish them — without it the engine reports
 * every outage as [UnreachableReason.Transport] (`core-schema-skew-surface.md` §8).
 *
 * ⚠⚠ **[reason] is a superset of [state], not a view of it.** [schemaFence] produces
 * [UnreachableReason.Fenced] while [state] is `true`: a fenced device answers every read and only
 * stops *pulling*, so it is simultaneously reachable and silently out of date. A shell that gates
 * its banner on `state == false` therefore cannot show that case at all — which is why every
 * shell gates on `reason != null` instead.
 *
 * ⚠ [UnreachableReason.NotSetUp] is the second reachable-branch case (ADR 0025 §Decision 8): a
 * Control with no realm answers every read — empty — so only [controlHasRealm] can tell.
 *
 * [scope] `null` (tests, previews) disables the probe loop; the state then tracks only organic
 * outcomes and [transportConnected].
 */
class ControlReachability(
    private val probe: suspend () -> Boolean,
    scope: CoroutineScope?,
    private val probeIntervalMs: Long = 5_000,
    private val onRecovered: suspend () -> Unit = {},
    private val transportConnected: StateFlow<Boolean>? = null,
    private val schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * `TenshuClient.proxyAnswerUnreadable` — the proxy **answered** and this build could not
     * read the answer (`CallOutcome.Unintelligible`).
     *
     * ⚠⚠ A second, independent route to the same verdict as [schemaSkew], and it catches the case
     * the announce cannot: a session that was admitted, and then met a type id this build has never
     * heard of. Without it, that reaches the user as *"Control is unreachable"* — an outage they
     * cannot fix, in place of the update they can.
     *
     * ⚠ [schemaSkew] wins when both are set: the announce compared two version vectors and knows
     * **which side** is behind, while an unreadable response only proves they differ.
     */
    private val answerUnreadable: StateFlow<Boolean>? = null,
    /**
     * Which binary owns this app's proxy (`core-schema-skew-surface.md` §8.4). Only consulted for
     * the fence case, where there
     * is no direction to derive a target from. Defaults to [ProxyHost.CONTROL] — correct for
     * Android, desktop and iOS EPC; route-1 runtimes pass [ProxyHost.THIS_APP].
     */
    private val proxyHost: StateFlow<ProxyHost?>? = null,
    /**
     * The realm schema pull fence (`core-schema-skew-surface.md` §8.3): the service ids this device
     * is behind on, or empty when it is in step. ⚠ Absent, the fence is simply never surfaced, and a
     * fenced device looks perfectly healthy while silently receiving nothing.
     */
    private val schemaFence: StateFlow<List<String>>? = null,
    /**
     * Asks the platform whether **Control is installed on this device** — `true`/`false`, or
     * `null` for *cannot tell*. Consulted only while the app is unreachable, on the probe's own
     * cadence, so it costs nothing on a healthy device.
     *
     * ⚠⚠ **Three answers, and `null` is a real one** (`ADR 0025`). Android answers from the package
     * manager and macOS from LaunchServices, but Windows and Linux have nothing to look at, an
     * unindexed Mac cannot be trusted to say "no", and an in-process runtime (iOS route-1, the web
     * runtime) is not looking for a separate Control binary at all. A two-valued `Boolean` would
     * force all of that ignorance into "installed" or "missing" and both are wrong where the user
     * can see it. `null` keeps the outage at [UnreachableReason.Transport] — the generic
     * words, which is what a platform that does not know should say.
     *
     * ⚠ iOS route-1 answers `null` here on purpose: its authoritative signal is ADR 0022's
     * `openURL` **attempt**, not a query, because `canOpenURL` cannot tell "not installed" from
     * "`LSApplicationQueriesSchemes` entry missing".
     *
     * ⚠ Polled rather than pushed, deliberately: installation state changes *while the app is on
     * screen* — the user taps through to the store, installs Control and comes back — and there is
     * no cross-platform install broadcast to subscribe to. The probe loop already ticks at exactly
     * the cadence that flow needs, so the answer refreshes on it instead of growing a second one.
     */
    private val controlInstalled: (suspend () -> Boolean?)? = null,
    /**
     * `TenshuClient.proxyAnswered` — one event per call the proxy **answered successfully**.
     *
     * ⚠⚠ **A reason to re-check, never an answer**, exactly as a reconnect on [transportConnected]
     * is: it wakes the probe and the probe decides. That is the whole difference between this and
     * a `markReachable` call, and it is not a nicety —
     *
     *  - a call is not a *read*, so a signal that set the state from here would be claiming
     *    something about reads it never saw;
     *  - going through the probe means the `false → true` flip runs the **recovery** ([readEpoch],
     *    [onRecovered]). An organic setter skips it, so a recovering app would keep hung
     *    [resubscribing] streams and an undrained outbox.
     *
     * ⚠ Silent on an in-process runtime, and silent while a proxy refuses the session: a refusal
     * is `CallOutcome.AnsweredWithError`, so this never fires for one. Absent, the engine has
     * only the probe's own cadence and the transport edge.
     */
    private val answered: Flow<Unit>? = null,
    /**
     * Asks Control whether **this device belongs to a realm** — `true`/`false`, or `null` for
     * *cannot tell* (`ADR 0025` §Decision 8). Android and desktop wire it through
     * [realmPresence] to `getUnlockStatus().isLocked` then `getSyncStatus()`'s resident `ownerId`,
     * the same two reads Control's own landing router takes to choose Locked / Home / onboarding.
     *
     * ⚠⚠ **`false` only from a successful read that carries no owner.** A failed read, a throw,
     * an app that does not register `:sync-service` — all `null`, which walls nothing. The
     * [controlInstalled] rule, for the same reason: telling a set-up user their
     * device is not set up is the mirror of telling them to install an app they already have.
     *
     * ⚠ The answer is a fact read *from* Control, so it is consulted only while [state] is `true`
     * and **dropped on every outage**: with Control unreachable it is stale, and the transport
     * reasons own the words. It is re-read on every recovery, then on [probeIntervalMs] for as
     * long as it is not `true` — so the wall lifts by itself once the user has set Control up —
     * and not again once it is (a healthy device pays one read per reconnect).
     *
     * ⚠ That latch is also why onboarding cannot flicker the wall: the resident owner only ever
     * goes absent → present while a realm is created or joined (it is persisted before the keys
     * are seated, and nothing on those paths clears it), and once read as present it is not
     * re-read. The cost is the reverse edge: a *Forget this device* in Control walls the app only
     * at its next reconnect, not live.
     *
     * Absent (iOS route-1, tests, previews), [UnreachableReason.NotSetUp] never fires.
     */
    private val controlHasRealm: RealmProbe,
) {

    private val _state = MutableStateFlow<Boolean?>(null)

    /** Last answer from [controlInstalled]; `null` until asked, and whenever it cannot tell. */
    private val _controlInstalled = MutableStateFlow<Boolean?>(null)

    /**
     * Last answer from [controlHasRealm]; `null` until asked, whenever it cannot tell, and
     * whenever [state] is not `true` (a stale answer must not outlive an outage).
     */
    private val _controlHasRealm = MutableStateFlow<Boolean?>(null)

    /** Whether [realmVerdict] can ever leave [RealmVerdict.UNKNOWN]: a realm probe AND a loop to run it. */
    private val realmGated: Boolean = controlHasRealm is RealmProbe.Wired && scope != null

    private val _realmVerdict =
        MutableStateFlow(if (realmGated) RealmVerdict.PENDING else RealmVerdict.UNKNOWN)

    /**
     * The realm question as a **write gate** for an app's unattended writers (`ADR 0025`
     * §Decision 8.6) — outbox drains, health imports, captured visits: work that would otherwise
     * write into a store that belongs to no realm.
     *
     * ⚠⚠ Four states, not [controlHasRealm]'s three, because "not asked yet" is not "cannot tell".
     * An app's launch pass and its recovery drain both fire on the very edge where Control becomes
     * reachable — *before* the realm read has answered — so a gate that let "unknown" through would
     * write into a no-realm store on exactly the path it exists to close. [RealmVerdict.PENDING]
     * covers that window: it is set on the way **into** reachable, ahead of [onRecovered].
     *
     * - [RealmVerdict.UNKNOWN] lets writes through, deliberately: no probe wired (iOS route-1,
     *   tests), the read failed or threw, or Control is unreachable (the write fails and is
     *   journalled). Only a *known* "no realm" holds work.
     * - ⚠⚠ A known [RealmVerdict.ABSENT] is left **only** for [RealmVerdict.PRESENT] — never by a
     *   failed read, an outage, `markUnreachable()`, a transport drop or a reconnect. Held work is
     *   released by a positive observation of a realm and nothing else (see
     *   [settleVerdictUnlessAbsent], which also says how "never observed" differs from "was ABSENT").
     */
    val realmVerdict: StateFlow<RealmVerdict> = _realmVerdict.asStateFlow()

    /** `true` while unattended writes must wait — [RealmVerdict.PENDING] or [RealmVerdict.ABSENT]. */
    val realmWritesHeld: Boolean get() = _realmVerdict.value.holdsWrites

    /**
     * The write-boundary gate (ADR 0025 §8.6): throws [RealmWritesHeldException] while
     * [realmWritesHeld]. Handed to every writer a gated pipeline uses and called before EACH synced
     * write or delete, so a call site cannot be missed.
     */
    fun refuseIfRealmWritesHeld() {
        if (realmWritesHeld) throw RealmWritesHeldException()
    }

    /**
     * Suspends until unattended writes may run ([RealmVerdict.PRESENT] or [RealmVerdict.UNKNOWN]).
     * Returns at once where no realm probe is wired. ⚠ May suspend for as long as the device has
     * no realm — that is the point: the held pass then runs when one appears.
     */
    suspend fun awaitRealmWritable() {
        _realmVerdict.first { !it.holdsWrites }
    }

    /**
     * Counts every held → writable transition, bumped by the **producer** inside [moveVerdict].
     *
     * ⚠⚠ A counter, not an edge a collector reconstructs from [realmVerdict]: a `StateFlow`
     * collector conflates, so one that is busy (running a drain) or slow to start can miss a
     * held → writable → held → writable sequence outright, and the work held in between stays
     * journalled until some unrelated trigger. A counter cannot lose a release — any change means
     * at least one happened — and comparing it against a marker taken *before* the initial work is
     * what makes "a release during the initial drain" produce a follow-up drain.
     */
    private val _releases = MutableStateFlow(0L)

    /** Take this **before** any initial work, then pass it to [onEachRealmWritableEdge]. */
    val realmReleaseMarker: Long get() = _releases.value

    /**
     * Runs [action] once per held → writable release since [since] — the **resumption** of held
     * work. ⚠⚠ A release is emitted for EVERY transition from a holding verdict to a writable one:
     * a realm appearing (ABSENT → PRESENT), and each recovery's or launch's realm read (PENDING →
     * PRESENT/UNKNOWN) — so a pass the gate held under PENDING is re-run too, not only one held
     * under ABSENT. Where no probe is wired (always UNKNOWN) it never fires.
     *
     * ⚠⚠ The rule (ADR 0025 §8.6): **subscribe before the initial work** — take [since] from
     * [realmReleaseMarker] before the first drain, so a release that lands during that drain still
     * runs [action] afterwards. Releases arriving while [action] runs coalesce into **one**
     * follow-up run; runs never overlap, because the collector is sequential.
     */
    suspend fun onEachRealmWritableEdge(since: Long = _releases.value, action: suspend () -> Unit) =
        onEachChange(_releases, since, action)

    private suspend fun onEachChange(counter: StateFlow<Long>, since: Long, action: suspend () -> Unit) {
        var seen = since
        counter.collect { count ->
            if (count != seen) {
                seen = count
                action()
            }
        }
    }

    /**
     * The one place [realmVerdict] changes. Atomic, and it bumps [_releases] from
     * the transition it actually made, so no observer has to infer an edge it may have conflated away.
     */
    private fun moveVerdict(next: (RealmVerdict) -> RealmVerdict) {
        while (true) {
            val current = _realmVerdict.value
            val target = next(current)
            if (_realmVerdict.compareAndSet(current, target)) {
                if (current.holdsWrites && !target.holdsWrites) _releases.update { it + 1 }
                return
            }
        }
    }

    /** `null` until the first outcome; then whether Control answered the last probe/read/write. */
    val state: StateFlow<Boolean?> = _state.asStateFlow()

    /**
     * The two routes to a skew verdict, kept **apart**.
     *
     * ⚠ Pre-combined into one source rather than passed as a sixth because `combine` takes five
     * typed arguments.
     *
     * ⚠⚠ Collapsing them to a single `Skew?` is wrong (pinned by
     * `recoveryClearsTheReasonEvenWhileTheSkewFlowIsStale`): the announced verdict
     * must NOT be surfaced on the reachable branch, because a recovered proxy whose skew flow has
     * not yet been re-announced is holding a **stale** value — and "a verdict that outlives its fix
     * tells the user to update something they already updated". The unreadable route has the
     * opposite requirement: it can only ever be observed while the proxy is answering. Same
     * verdict, opposite branch, so the origin has to survive.
     */
    private data class SkewSources(
        val announced: UnreachableReason.Skew?,
        val unreadable: Boolean,
    )

    /**
     * The two platform/proxy facts about Control itself — installed, and set up — pre-combined
     * for the same reason as [SkewSources]: `combine` takes five typed arguments.
     */
    private data class ControlPresence(
        val installed: Boolean?,
        val verdict: RealmVerdict,
    )

    /**
     * ⚠⚠ The realm half is the **verdict**, not the last raw read. The raw read is reset on every
     * outage, so a device known to have no realm would show the shell again through each recovery
     * until a re-read landed; the verdict remembers ABSENT until a read sees a
     * realm, and says PENDING while nothing has answered — both of which the roots must cover.
     */
    private val presence: Flow<ControlPresence> =
        combine(_controlInstalled, _realmVerdict) { installed, verdict ->
            ControlPresence(installed, verdict)
        }

    private val skewSources: Flow<SkewSources> =
        combine(
            schemaSkew ?: MutableStateFlow(null),
            answerUnreadable ?: MutableStateFlow(false),
        ) { announced, unreadable -> SkewSources(announced, unreadable) }

    /**
     * *Why* Control is unavailable, or `null` while it is fine or not yet known
     * (`core-schema-skew-surface.md` §8). The shell renders a banner off [state]`== false` and
     * picks its **words** from this.
     *
     * ⚠⚠ Skew wins over [UnreachableReason.Transport] whenever both could apply, and that
     * ordering is the whole point. A refused announce also makes every probe fail, so a skewed
     * device satisfies the transport case too — resolving it the other way would restore exactly
     * the "Can't reach Control" mislabel this exists to remove.
     *
     * ⚠⚠ A [StateFlow] whose `value` is computed **synchronously** from the sources. A cold `Flow`
     * collected with `initial = null` would render the interactive shell on the very first
     * composition — and run its effects — while the verdict is already PENDING. A root's first
     * frame reads the real reason: with a wired probe and nothing known yet, that is
     * [UnreachableReason.Checking], never the shell (`RealmProbeWiringTest` pins the roots).
     *
     * ⚠ Not `stateIn`: [scope] is `null` in tests and previews, so there is no scope to share
     * into. Every source is a `StateFlow`, so the current reason is a pure function of their
     * current values ([reasonOf]) and needs no collector to be right.
     */
    val reason: StateFlow<UnreachableReason?> = DerivedStateFlow(
        now = {
            reasonOf(
                _state.value,
                SkewSources(schemaSkew?.value, answerUnreadable?.value ?: false),
                schemaFence?.value ?: emptyList(),
                proxyHost?.value,
                ControlPresence(_controlInstalled.value, _realmVerdict.value),
            )
        },
        changes = combine(
            _state,
            skewSources,
            schemaFence ?: MutableStateFlow(emptyList()),
            proxyHost ?: MutableStateFlow(null),
            presence,
            ::reasonOf,
        ),
    )

    private fun reasonOf(
        reachable: Boolean?,
        skew: SkewSources,
        fencedServices: List<String>,
        host: ProxyHost?,
        control: ControlPresence,
    ): UnreachableReason? =
        when {
            // Skew B first: it is a hard refusal — nothing loads at all — and it is also the
            // one the user can act on soonest.
            reachable == false && skew.announced != null -> skew.announced
            // ⚠ Below skew and above transport. It cannot in fact collide with skew — a
            // missing Control answers no announce, so there is no version to disagree on —
            // but ordering it explicitly means the precedence does not depend on that being
            // true forever. Above [UnreachableReason.Transport] because it *is* a transport
            // outage, refined: same dead channel, one knowable cause, different words.
            reachable == false && control.installed == false -> UnreachableReason.NotInstalled
            reachable == false -> UnreachableReason.Transport
            // ⚠⚠ On the REACHABLE branch, and for exactly the reason the fence below is. An
            // unreadable *answer* means the proxy is answering — so `reachable` is `true`, and
            // gating this on `reachable == false` like the refusal above would give it no
            // surface at all — the same trap as the fence below.
            //
            // ⚠⚠ `unreadable` only — NOT `announced`. A recovered proxy can still be holding a
            // stale announced verdict until it re-announces, and surfacing that here tells the
            // user to update something they already updated
            // (pinned by `recoveryClearsTheReasonEvenWhileTheSkewFlowIsStale`).
            skew.unreadable -> UnreachableReason.Skew(UpdateTarget.BOTH)
            // ⚠⚠ REACHABLE branch only (ADR 0025 §Decision 8): the answer was read from
            // Control, so it means nothing while Control is not answering. Below the
            // unreadable skew — a session that cannot read answers cannot trust this one
            // either — and above the fence, which presupposes a realm to be behind.
            //
            // ⚠⚠ From the VERDICT, so a remembered ABSENT walls again the moment Control is
            // back — before any re-read has answered — and stays until a read sees a realm.
            // (`reachable == false` is handled above: the transport reasons own an outage.)
            control.verdict == RealmVerdict.ABSENT -> UnreachableReason.NotSetUp
            // ⚠⚠ Nothing has answered the realm question yet while Control is reachable (or not
            // yet known): the roots show the non-interactive checking state, never the shell —
            // so no user write can reach a Control with no realm in that window. Only a wired probe
            // ever reports PENDING; unwired is UNKNOWN.
            control.verdict == RealmVerdict.PENDING -> UnreachableReason.Checking
            // ⚠⚠ Evaluated on the REACHABLE branch, deliberately. A fenced device answers
            // every read; only its pull is stopped. Testing the fence after `reachable !=
            // false -> null` would leave this case no surface: the guard would return null
            // before the fence was ever consulted.
            fencedServices.isNotEmpty() ->
                UnreachableReason.Fenced(host?.toUpdateTarget(), fencedServices)
            else -> null
        }


    private val _readEpoch = MutableStateFlow(0)

    /** Bumped on recovery; read flows re-subscribe via [resubscribing]. Public for tests. */
    val readEpoch: StateFlow<Int> = _readEpoch.asStateFlow()

    /**
     * Wakes the probe loop early. Conflated: a reconnect that arrives mid-probe is remembered so
     * the *next* wait returns at once, but a burst of them never queues up extra probes.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Bumped by [recheck]; the realm-read loop's wait between reads returns early on a bump. */
    private val recheckRequests = MutableStateFlow(0)

    /**
     * Ask again now — the user tapped "Try again" on the checking gate's retry wall (wired by every
     * root as `onRetry`, not a timer of the gate's own). Not an answer, only a reason to ask:
     * it wakes the probe (the same wake a transport reconnect uses) and cuts short the realm-read
     * loop's wait, so the next realm read runs at once instead of after the poll interval.
     */
    fun recheck() {
        recheckRequests.value += 1
        wake.trySend(Unit)
    }

    init {
        scope?.launch {
            transportConnected?.let { connected ->
                launch {
                    connected.collect { up ->
                        if (up) {
                            // Not an answer, only a reason to ask again — see the class doc.
                            wake.trySend(Unit)
                        } else {
                            // Authoritative: nothing can reach a proxy across a dead channel.
                            _state.value = false
                        }
                    }
                }
            }

            // The same treatment for the same reason: evidence, not a verdict. See [answered].
            answered?.let { events ->
                launch { events.collect { wake.trySend(Unit) } }
            }

            (controlHasRealm as? RealmProbe.Wired)?.read?.let { hasRealm ->
                launch {
                    _state.collectLatest { reachable ->
                        if (reachable != true) {
                            // Dropped, not kept: a verdict read before the outage may have been
                            // fixed during it (the user set Control up), and the reachable
                            // branch that consults it is not live anyway.
                            _controlHasRealm.value = null
                            // ⚠ `false` only — `null` is "never answered yet", and the launch
                            // pass must keep waiting through it (PENDING), not run (UNKNOWN).
                            // ⚠⚠ And never out of a HOLDING verdict: an outage is not an answer to the
                            // realm question. ABSENT stays ABSENT, and PENDING stays PENDING until a
                            // realm read actually answers (else an organic `markUnreachable()`
                            // would turn the initial PENDING into UNKNOWN and open the gate before
                            // the first realm read had said anything).
                            //
                            // ⚠⚠ And a remembered PRESENT becomes RECHECKING, not a writable UNKNOWN:
                            // the transport can reconnect before the probe —
                            // let alone the realm read — has said anything, and a Control reset or a
                            // device forgotten during the outage has no realm. RECHECKING holds the
                            // unattended writers but keeps the shell; only a realm read moves it.
                            if (reachable == false) moveVerdict { current ->
                                when {
                                    current == RealmVerdict.PRESENT -> RealmVerdict.RECHECKING
                                    current.holdsWrites -> current
                                    else -> RealmVerdict.UNKNOWN
                                }
                            }
                            return@collectLatest
                        }
                        // Normally already PENDING (set on the way in, ahead of onRecovered); this
                        // covers a `true` that arrived by another route.
                        settleVerdictUnlessKnownHeld(RealmVerdict.PENDING)
                        var failedReads = 0
                        while (true) {
                            val rechecksSeen = recheckRequests.value
                            // A recheck during the read cancelled it: no answer, so the verdict
                            // is untouched — read again now, not after the wait below.
                            if (refreshControlHasRealm(hasRealm, rechecksSeen)) continue
                            when (_controlHasRealm.value) {
                                // The ONLY way out of ABSENT or RECHECKING: a read that saw a realm.
                                true -> moveVerdict { RealmVerdict.PRESENT }
                                false -> moveVerdict { RealmVerdict.ABSENT }
                                // ⚠⚠ A read that could not answer — a session not yet admitted, an
                                // IPC failure, a timeout — is not an answer, so it never moves a
                                // HOLDING verdict: PENDING stays PENDING, ABSENT stays ABSENT. Right
                                // after a Control restart an app's realm read lands ~100 ms before
                                // its schema announce is admitted, and turning that failure into
                                // UNKNOWN would open the gate until the next poll.
                                null -> settleVerdictUnlessHolding(RealmVerdict.UNKNOWN)
                            }
                            // Once known set up, stay quiet until the next recovery re-enters.
                            if (_controlHasRealm.value == true) break
                            // While still PENDING, retry on a short backoff rather than the normal
                            // poll, so a launch just after a Control restart settles in about a
                            // second instead of a whole probe interval.
                            val wait = if (_realmVerdict.value.awaitsAnAnswer) {
                                minOf(PENDING_RETRY_BACKOFF_MS.getOrElse(failedReads++) { probeIntervalMs }, probeIntervalMs)
                            } else {
                                probeIntervalMs
                            }
                            // …or sooner, when the user asks ([recheck]).
                            withTimeoutOrNull(wait) { recheckRequests.first { it != rechecksSeen } }
                        }
                    }
                }
            }

            _state.collectLatest { reachable ->
                if (reachable != true) {
                    // Before the first wait, not after it: the banner is on screen *now*, and a
                    // five-second window of the wrong words is the whole failure being fixed.
                    refreshControlInstalled()
                    while (true) {
                        awaitNextProbe()
                        // ⚠ Ahead of the dead-channel skip below. A local package query needs no
                        // channel, and this is precisely the state it exists to explain — skipping
                        // it there would silence it exactly when it is the only thing worth saying.
                        refreshControlInstalled()
                        // Skip the round trip entirely while the channel is known down: it can
                        // only fail, and the transport already told us the answer it would give.
                        if (transportConnected?.value == false) continue
                        if (probe()) {
                            val wasDown = _state.value == false
                            // ⚠⚠ Before `_state` and so before [onRecovered]: a gated app's
                            // recovery drain must see PENDING, not the outage's UNKNOWN, or it
                            // writes before the realm read has answered (§Decision 8.6).
                            if (realmGated) settleVerdictUnlessKnownHeld(RealmVerdict.PENDING)
                            _state.value = true
                            if (wasDown) {
                                _readEpoch.value += 1
                                onRecovered()
                            }
                            break
                        } else {
                            _state.value = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Moves [realmVerdict] to [next] unless it currently holds writes — PENDING, ABSENT or
     * RECHECKING. Used when a realm read could not answer.
     *
     * ⚠⚠ **Held work is released only by a positive observation of a realm** (ADR 0025 §8.6). A
     * holding verdict is left by a realm read that answers, and never by an outage, a
     * `markUnreachable()`, a transport drop, a reconnect, or a read that could not tell: ABSENT only
     * for PRESENT, PENDING and RECHECKING only for PRESENT or ABSENT. The verdict itself is the
     * memory that tells "never observed" (a fresh PENDING) from "was ABSENT" or "was PRESENT, and
     * Control has been away since" (RECHECKING).
     */
    private fun settleVerdictUnlessHolding(next: RealmVerdict) {
        moveVerdict { current -> if (current.holdsWrites) current else next }
    }

    /**
     * The way into reachable: PENDING, unless the verdict already remembers something a realm read
     * must settle — ABSENT (keeps the wall) or RECHECKING (keeps the shell, holds the writers).
     */
    private fun settleVerdictUnlessKnownHeld(next: RealmVerdict) {
        moveVerdict { current ->
            if (current == RealmVerdict.ABSENT || current == RealmVerdict.RECHECKING) current else next
        }
    }

    /**
     * Re-asks the platform whether Control is installed. A probe that throws is read as *cannot
     * tell* rather than *missing*: telling a user to install an app they already have is the
     * mirror of the defect this closes, and a package query can legitimately fail (a manifest
     * `<queries>` entry that was never added answers "not installed" for a Control that is right
     * there — `ui-core`'s `ControlLauncher` carries the same warning).
     */
    private suspend fun refreshControlInstalled() {
        val probe = controlInstalled ?: return
        _controlInstalled.value = try {
            probe()
        } catch (e: CancellationException) {
            // ⚠⚠ Never swallowed: the probe runs inside the loop's scope, so eating this would
            // leave a cancelled engine still ticking. The same rethrow every app's read probe does.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-asks Control whether this device has a realm; a throw (or a read that outlives
     * [REALM_READ_TIMEOUT_MS]) is *cannot tell*, never *no*.
     *
     * ⚠ A *known* `false` survives a later "cannot tell" for as long as Control stays reachable (the
     * outage path resets it): one failed re-read while the wall is up must neither drop the wall for
     * a probe interval nor release the writes it holds.
     *
     * ⚠⚠ The read is raced against [recheck], so a read hung in flight does not sit out its whole
     * [REALM_READ_TIMEOUT_MS] before a "Try again" can do anything. A recheck
     * newer than [rechecksSeen] cancels the read, and this returns `true` — *interrupted* — so the
     * caller starts a fresh read at once. An interrupted read is **no answer**: it leaves
     * [_controlHasRealm] and the verdict exactly as they were (a held verdict stays held), and its
     * result is discarded even if it answered in the same instant, so nothing stale can land later.
     */
    private suspend fun refreshControlHasRealm(probe: suspend () -> Boolean?, rechecksSeen: Int): Boolean {
        var interrupted = false
        val answer = try {
            withTimeoutOrNull(REALM_READ_TIMEOUT_MS) {
                coroutineScope {
                    val read = async { probe() }
                    val watcher = launch {
                        recheckRequests.first { it != rechecksSeen }
                        interrupted = true
                        read.cancel()
                    }
                    try {
                        read.await()
                    } catch (e: CancellationException) {
                        // Ours (the recheck) is "no answer"; anyone else's is a real cancellation.
                        if (interrupted) null else throw e
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (interrupted) return true
        _controlHasRealm.value = answer ?: if (_controlHasRealm.value == false) false else null
        return false
    }

    private companion object {
        /** A realm read is two local proxy round trips; one hung past this is "cannot tell". */
        const val REALM_READ_TIMEOUT_MS = 15_000L

        /** Retry delays while PENDING after a read that could not answer; then the normal poll. */
        val PENDING_RETRY_BACKOFF_MS = longArrayOf(250L, 500L, 1_000L, 2_000L)
    }

    /** Waits out [probeIntervalMs], or returns early on a wake: a reconnect, an answer, or [recheck]. */
    private suspend fun awaitNextProbe() {
        // ⚠ Always the channel, never a plain `delay`: [recheck] can wake every engine, including
        // one with neither a transport signal nor an answered signal.
        withTimeoutOrNull(probeIntervalMs) { wake.receive() }
    }

    /**
     * Declare the proxy reachable outright.
     *
     * ⚠⚠ **`internal`, and that visibility is the design.** No caller outside this module can be
     * trusted to call it from a read fold: a "success" there may be a
     * value served from a cache, and asserting reachability on one **cancels the probe** — the
     * loop collects [state] with `collectLatest` and runs only while it is not `true` — which
     * silences the one check that finds a live-but-refusing proxy. [answered] is the supported
     * route: it wakes the probe instead of answering for it.
     *
     * ⚠ It also skips the recovery. [readEpoch] and [onRecovered] fire on the probe's own
     * `false → true` flip, so a state set from here can beat the probe to a recovery and drop it,
     * leaving hung [resubscribing] streams dead and Fit's outbox undrained. That is why the tests
     * that call this are testing *state*, never recovery.
     */
    internal fun markReachable() {
        _state.value = true
    }

    /**
     * An organic failure — a read or write that could not reach the proxy.
     *
     * ⚠⚠ **Most callers cannot know this, and saying it anyway is a bug.** A *thrown*
     * read is not evidence the proxy is gone: a dead transport folds into a typed `TResult.Failure`
     * on both platforms rather than throwing (`BinderTaskTransport.asNotConnected`,
     * `UnixSocketProxyDeathTest`), so what still throws is usually client-side — an unregistered
     * schema, a parse — and reporting it here puts the Control-unreachable banner over something no
     * amount of waiting fixes. A *folded* failure is no better on its own: over a live channel it
     * may be a schema refusal, which is the probe's to detect and [reason]'s to word.
     *
     * ⚠ [transportConnected] already reports a dead channel instantly and authoritatively, which is
     * most of what this is for. Photos calls it nowhere — it classifies its failures from
     * `proxyConnected` at the fold instead, and that is the shape the remaining apps should
     * converge on, at which point this can follow [markReachable] behind `internal`.
     */
    fun markUnreachable() {
        _state.value = false
    }

    /**
     * Wraps a read stream so it is re-subscribed whenever [readEpoch] bumps (probe-driven
     * recovery) — a stream that hung or died against a dead transport never recovers on its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> resubscribing(build: () -> Flow<T>): Flow<T> =
        readEpoch.flatMapLatest { build() }
}

/**
 * A [StateFlow] over sources that are all `StateFlow`s: `value` is computed on demand from their
 * current values, and collection follows their combination. No scope, so no collector to leak.
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow<T>(
    private val now: () -> T,
    changes: Flow<T>,
) : StateFlow<T> {
    private val distinct = changes.distinctUntilChanged()
    override val value: T get() = now()
    override val replayCache: List<T> get() = listOf(now())
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
        distinct.collect(collector)
        kotlinx.coroutines.awaitCancellation()
    }
}
