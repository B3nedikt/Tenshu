package org.tenshu.inference.proxy

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.proxy.FileDatabase

/**
 * [AiReadinessState] **with the install it was reconciled against**, as [AiReadiness] publishes
 * it for a screen (`ios-on-device-inference.md` §14.18).
 *
 * ⚠⚠ A state alone says nothing of *which* install. A screen reads the store and readiness
 * separately; after an update commits it could pair the new record with the old `Ready`, for
 * as long as readiness's own change took to arrive, and show as ready a generation that had
 * passed neither its prepare nor its probe. With the key the screen compares,
 * whole, and a state about another install is *not read yet* for this one.
 * ⚠ The key is the [PreflightKey], the whole one a verdict is bound to: both archives' digests,
 * the vocabulary's, the OS build, the app build, the revision. Not the install record's
 * identity, which is narrower: `Ready` is a statement about everything in that key.
 * ⚠ Three values, like every other source: the flow starts at [NotReadYet] (readiness's state
 * starts at `NoModel`, which is no reading), and a read that failed is [Unreadable].
 */
sealed interface ReadinessStanding {
    data object NotReadYet : ReadinessStanding
    data object Unreadable : ReadinessStanding
    /**
     * @param stoppedLoading [state] is `Unsuitable` because loads of [key] were interrupted, and
     *   for no other reason ([PreflightVerdict.stoppedByInterruptedLoads]): the one `Unsuitable` a
     *   user may ask to try once more (§14.5 item 0).
     */
    data class For(val key: PreflightKey, val state: AiReadinessState, val stoppedLoading: Boolean = false) : ReadinessStanding
}

/** Where a device that passed [AiStaticGates] stands with its model (§3.2, *Readiness*). */
sealed interface AiReadinessState {
    /** No install record. */
    data object NoModel : AiReadinessState

    /** Installed; not every tower has a ready marker for the installed digests yet. */
    data object Preparing : AiReadinessState

    /** Prepared; no persisted preflight verdict for the current fingerprint. */
    data object AwaitingVerdict : AiReadinessState

    /** Prepared, and a persisted **pass** for the current fingerprint. The one state that serves. */
    data object Ready : AiReadinessState

    /** A persisted **fail** for the current fingerprint. */
    data object Unsuitable : AiReadinessState
}

/** The compiled towers and the vocabulary of one installed generation. */
data class PreparedModel(
    val visionPath: String,
    val textPath: String,
    val vocabularyPath: String,
)

/**
 * What the model store and the compiled cache hold now.
 *
 * @param prepared `null` unless **every** part description search needs is there: both towers'
 *   ready markers for the installed digests, and the vocabulary (D12: a vision-only pass would
 *   enable a search whose every query fails).
 */
data class AiModelFacts(val installed: Boolean, val prepared: PreparedModel?)

/**
 * Everything a preflight verdict is an answer **about**: a pass stands only while all of it does.
 *
 * ⚠⚠ **Not the [RuntimeFingerprint], and never to be passed where one is wanted.** That one
 * answers *"may these stored vectors stay?"* and is the custody's. This one answers *"has what
 * would run now been checked?"* and is readiness's. The preflight checks more than what makes a
 * vector, so this key is wider:
 * - [runtime]: both archive digests, the compute units, the OS build, the preprocessing revision;
 * - [vocabularySha256]: a catalog update can replace the vocabulary and keep both towers. No
 *   stored vector changes, so the custody must not clear; every query's tokens do, so the pass
 *   is void;
 * - [preflightRevision]: **the code the pass vouches for**, [PREFLIGHT_REVISION]. An app update
 *   can change the tokenizer, the backend's guards or the preflight itself with every digest and
 *   constant above unchanged, and this is what then voids the pass;
 * - [appBuild]: the bundle's version and build. ⚠⚠ **Constant today, so it vouches for nothing.**
 *   No iOS project in this repo sets a version: every build reads `1.0 (1)`. It is in the key
 *   because it costs nothing and starts to bite the day shipped builds carry distinct numbers.
 *   Until then [preflightRevision] is the only guard against a code change.
 *
 * ⚠ No field has a default, on purpose: a file written under another shape of this key does not
 * decode, and what does not decode is no verdict.
 */
@Serializable
data class PreflightKey(
    val runtime: RuntimeFingerprint,
    val vocabularySha256: String?,
    val appBuild: String,
    val preflightRevision: Int,
)

/**
 * The preflight and the code a passing verdict vouches for, as a number. A pass persisted under
 * another value is asked for again.
 *
 * ⚠⚠ **Bump it by hand, in the same change, whenever any of these changes what a query or a
 * photo becomes, or what is accepted as a pass:**
 * - the tokenizer: `SiglipTextTokenizer`, `nfkc` on any platform, `UnigramVocabulary`'s reader;
 * - the guards on a vector: `TowerGuards` (shape, pooled output, width, finite, non-zero), and
 *   where `CoreMlInferenceBackend` and `CoreMlSession` apply them;
 * - how a session is configured or fed: the compute units are in the fingerprint already, the
 *   multi-array layout and the input and output names are not;
 * - the preflight itself: the probe image and captions, the ranking margin, the latency rows,
 *   the retry rule, what counts as `Unsuitable` ([Preflight], [PreflightJudge], [PreflightBudget],
 *   [PreflightPolicy]), and the content of an **existing** probe record.
 *
 * ⚠⚠ **Not for adding a probe record for another export.** The key already holds both archive
 * digests and the vocabulary's, so a new export cannot stand on an old verdict. A bump there
 * would void the verdict of every phone that still holds an earlier export, and buy nothing.
 * Removing a record needs none either; whether to remove one is another question
 * (`docs/how-to/models-pipeline.md` §2b).
 *
 * ⚠ **Not** the image transform (`IosImageDecoder`, `ImagePreprocessing`): that is
 * `IOS_PREPROCESSING_REVISION`, which is in the [RuntimeFingerprint] because it also decides
 * whether stored vectors stay. One constant per question; a change there needs no bump here.
 *
 * ⚠ A hand-bumped constant decays. `PreflightRevisionPinTest` pins the tokenizer's, the guards'
 * and the judge's observable behaviour beside this number and fails when they move.
 *
 * Revisions: 1 has no preflight to vouch for anything. 2 is the first preflight: the probe, the
 * judge and the retry rules. 3 counts a load the process did not
 * survive ([AttemptBegun], [PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE]).
 */
const val PREFLIGHT_REVISION: Int = 3

/**
 * The probes that ended without a verdict, counted (D12). Kept **in the verdict's file and under
 * its key**, so a new archive, vocabulary, OS build or preflight revision starts them at zero.
 *
 * @param slow slow or over-budget probes, each started at an acceptable thermal state and each on
 *   its own foreground. [PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE] of them are a verdict.
 * @param failed probes in which a tower or the vocabulary would not load, or the output was
 *   wrong. They never make a phone `Unsuitable`: past [PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL]
 *   the install is what failed. ⚠ Writer 3 clears this one: a commit or a removal is a new install.
 * @param transient probes that did not pass on a phone that was hot when they started, or was
 *   warned about memory while they ran. They decide nothing, however many there are.
 * @param lastAtEpochMs when the last of them ended, for the backoff. 0 when there was none.
 * @param interrupted loads of this key's towers (a probe, or a prepare's first open) that began
 *   and that **the process did not survive**: an [AttemptBegun] a later process found (§14.5).
 *   [PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE] of them are a verdict. ⚠ Like [slow], the
 *   phone's: a commit or a removal does not clear it.
 */
@Serializable
data class PreflightAttempts(val slow: Int, val failed: Int, val transient: Int, val lastAtEpochMs: Long, val interrupted: Int = 0) {
    companion object {
        val NONE = PreflightAttempts(slow = 0, failed = 0, transient = 0, lastAtEpochMs = 0)
    }
}

/** Which load an [AttemptBegun] is about. */
enum class AttemptKind {
    /** The preflight's probe: both towers opened and predicted on. */
    PROBE,

    /** One tower's unpack, compile and move in a prepare, up to its first open. */
    COMPILE,

    /** One tower's first open in a prepare, after its compile ([PrepareStep.FIRST_OPEN]). */
    FIRST_OPEN,

    /**
     * One tower opened by the backend while `Ready`: a search's, the warm-up's or an index
     * pass's (§14.19). ⚠ Recorded in its tower's own file, never in the verdict's
     * ([AiReadiness.servingOpen]): the backend opens under its tower's lock, which a writer of the
     * verdict's file waits for while it stops serving.
     */
    SERVE_OPEN,
}

/**
 * ⚠⚠ **A load of the towers that has begun and not yet ended**, on disk (D12, §14.5): written
 * before the load, in the verdict's file beside whatever it holds, and replaced or cleared when the load
 * ends **in any way the process lives through**. A record a later process finds was a load that
 * process did not survive (jetsam, a Core ML abort), and is counted as
 * [PreflightAttempts.interrupted]. Without it a phone on which loading the towers kills Photos
 * loaded them again at every launch and every return to the foreground.
 *
 * @param key the whole key of what was loaded: counted against it and nothing else, so a record
 *   from before an OS update or a new install is not counted against the new one.
 * @param role the tower, for a [AttemptKind.FIRST_OPEN]; `null` for a probe.
 * @param process the [AttemptLedger] of the process that began it, and [serial] which of its loads.
 * @param foreground which foreground of the scene it began in, or `null` in none. ⚠⚠ What tells
 *   a load that was frozen when Photos left the foreground (and then ended by iOS, which kills
 *   suspended apps freely) from one that was killed in front of the user: see [SceneLeft].
 */
@Serializable
data class AttemptBegun(
    val key: PreflightKey,
    val kind: AttemptKind,
    val role: String?,
    val process: String,
    val serial: Long,
    val atEpochMs: Long,
    val foreground: Long?,
)

/**
 * ⚠⚠ The scene left the foreground while [process] was in foreground [leftForeground], or `null`:
 * it is in the foreground again (or never left). Its own file, written at the moment the scene
 * changes, with no lock: nothing else writes it, and the process is alive then.
 *
 * A load whose record names this process and a foreground up to [leftForeground] was frozen in
 * the background by iOS, not killed in front of the user: a later process counts it as transient
 * (the backoff), never as interrupted. A suspended app's jetsam or 0xdead10cc, and a swipe-away
 * from the app switcher (the scene went to the background first, or, for an app swiped away
 * while it was still running, `applicationWillTerminate` wrote this record), take that path; a
 * kill in the foreground does not (the scene had not left, or the record was cleared when it came
 * back). ⚠ Not `.inactive`: Control Center, a call or the switcher itself is a foreground in which
 * a jetsam is a kill in front of the user.
 */
@Serializable
data class SceneLeft(val process: String, val leftForeground: Long?)

/**
 * The scene file: one [SceneLeft] per process whose record still matters. ⚠⚠ Not one record: the
 * next process would write its own over the earlier one's before its first read of the verdict,
 * and that earlier process's frozen load would then be counted as a kill.
 * Kept: this process's own, and the one of the process whose load the verdict's file still holds.
 */
@Serializable
internal data class SceneRecords(val left: List<SceneLeft>)

/**
 * The loads **this process** has begun and not ended (§14.5): what tells a [AttemptBegun] of a
 * dead process from one still running here, in this graph or in another graph of the process.
 * ⚠ Memory, and rightly: it is what *this process* is, and a new process is a new ledger.
 * ⚠ Process-wide ([THIS_PROCESS]); a test makes a second "process" with a second ledger.
 */
class AttemptLedger(val process: String = newProcessId()) {
    private val serials = MutableStateFlow(0L)
    private val running = MutableStateFlow<Set<Long>>(emptySet())

    fun open(): Long {
        val serial: Long = serials.updateAndGet { it + 1 }
        running.update { it + serial }
        return serial
    }

    fun close(serial: Long) = running.update { it - serial }

    fun isRunning(serial: Long): Boolean = serial in running.value

    private val locks = MutableStateFlow<Map<String, Mutex>>(emptyMap())

    /**
     * ⚠⚠ The one lock of [path] **in this process**. The verdict's file is the process's, and every
     * graph's readiness reads, modifies and writes it: a lock per graph let a retired graph and its
     * successor lose each other's writes (a load's clear and writer 3's reset, interleaved).
     */
    fun lockFor(path: String): Mutex {
        locks.update { held -> if (path in held) held else held + (path to Mutex()) }
        return locks.value.getValue(path)
    }

    companion object {
        val THIS_PROCESS: AttemptLedger = AttemptLedger()

        private fun newProcessId(): String = Random.nextLong().toULong().toString(16) + "-" + Random.nextLong().toULong().toString(16)
    }
}

/** Why a load was not begun: its [AttemptBegun] could not be written first. Nothing was loaded. */
class AttemptNotRecordedException(val why: Why, message: String) : Exception(message) {
    enum class Why {
        /** The verdict's file could not be read, or the record could not be written. */
        UNREADABLE,

        /** What would run now is not what the load was for, or it has its answer already. */
        MOVED,

        /** The readiness was retired. */
        RETIRED,
    }
}

/**
 * The preflight's answer, and what it was an answer about. Device-local, in Photos' private
 * files beside the realm stamp; never synced, and no schema's (House Rule 9 does not reach it).
 *
 * @param passed `null` while there is no answer yet, only [attempts] at one.
 * @param begun a load of [key]'s towers that is running, or that its process did not survive.
 */
@Serializable
data class PreflightVerdict(val key: PreflightKey, val passed: Boolean?, val attempts: PreflightAttempts, val begun: AttemptBegun? = null) {
    /**
     * A fail that loads Photos did not survive made, and nothing else: not three slow probes.
     * ⚠ The one fail a user may ask to try once more ([AiReadiness.loadAgain]).
     */
    val stoppedByInterruptedLoads: Boolean
        get() = passed == false && attempts.slow < PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE &&
            attempts.interrupted >= PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE
}

/** How one probe ended, as the preflight's judge found it. */
enum class ProbeResult { PASSED, SLOW, FAILED, TRANSIENT }

/**
 * What readiness **did** with a probe's result. The preflight reports nothing it was not handed
 * here: a result about a model that has since been replaced, or handed to a retired readiness,
 * changes nothing and must not be announced as if it had.
 */
sealed interface ProbeRecorded {
    /**
     * Nothing was taken from it: the key moved under the probe, the model is gone, the readiness
     * is retired, there was an answer already, or it could not be read or written.
     */
    data class Dropped(val state: AiReadinessState) : ProbeRecorded

    /** Counted under the probed key. The state is still `AwaitingVerdict`. */
    data class Counted(val attempts: PreflightAttempts) : ProbeRecorded

    /** The answer for the probed key, which was current under the lock: `Ready` or `Unsuitable`. */
    data class Decided(val passed: Boolean, val state: AiReadinessState, val attempts: PreflightAttempts) : ProbeRecorded
}

/** What a preflight is to probe: the prepared model no verdict covers yet, and what was tried. */
class ProbeTarget(val key: PreflightKey, val model: PreparedModel, val attempts: PreflightAttempts)

/** What [AiReadiness.awaitingProbe] found. */
sealed interface Awaiting {
    /** A prepared model with no verdict for the current key: probe it. */
    class Probe(val target: ProbeTarget) : Awaiting

    /** Nothing to probe: no model, not prepared, an answer already, or a retired readiness. */
    data object NothingToProbe : Awaiting

    /**
     * ⚠⚠ What readiness is derived from could not be **read**. That is not *nothing to probe*
     * and it is not *no verdict yet*: a probe run now would be judged against counts that may
     * exist and could not be seen, and its result would have to be thrown away. Nothing is
     * probed, nothing is served, and nothing on disk is touched.
     */
    data object Unreadable : Awaiting
}

/**
 * The one live state that decides whether a bound backend **claims** anything
 * (`ios-on-device-inference.md` §3.2, *Readiness*; §7 D12).
 *
 * The backend and the search route are bound once, at assembly, and never rebound. Everything
 * that can change in a running process changes here: the registry asks `supports` on every call,
 * and the backend answers from [serves].
 *
 * ⚠⚠ **The state is derived, never set.** Every writer re-reads the same three things — the
 * install record, the ready markers, the persisted verdict — and moves to what they say. The
 * verdict is keyed on the [PreflightKey], which holds every artifact's digest, so a replacement
 * archive has no verdict by construction and cannot be served on its predecessor's.
 *
 * ⚠⚠ **A change of state is ordered** (§3.2), because one `ModelProvenance` covers every artifact
 * and the embedding store would accept a new artifact's vectors beside the old one's:
 * - leaving [AiReadinessState.Ready], for any reason: flip the snapshot, cancel a running pass,
 *   close the sessions;
 * - entering it: apply the fingerprint (which clears vectors another archive made), persist the
 *   verdict, flip the snapshot **last**.
 *
 * **How this relates to [InferenceStoreCustody].** The custody decides whether the *stores* may be
 * read or written: which realm, and which runtime made the vectors. This decides whether the
 * *model* may run. Neither repeats the other's check. [serves] is their conjunction, read at the
 * moment of the call: the custody's answer is not copied into the snapshot, because it changes on
 * its own schedule (a realm reset seen at a gate) and a copy would be stale exactly then.
 *
 * ⚠⚠ **[retire] is terminal.** The graph this object lives in can outlive its runtime: a view
 * model keeps it reachable after a sign-out. A retired readiness serves nothing, and no writer
 * moves it again, so the backend bound over it can neither claim a capability nor open a model.
 *
 * ⚠ Starts as [AiReadinessState.NoModel] and reads nothing until a writer runs: the first read
 * constructs the model store, which belongs off the launch path.
 *
 * @param files the owner's private database, asked for by the first writer and not before:
 *   opening it touches the disk.
 * @param facts asked by every writer, never by [serves].
 * @param storesUsable `InferenceStoreCustody.usable`. Asked per call, and only while `Ready`.
 * @param applyFingerprint `InferenceStoreCustody.applyFingerprint`.
 * @param cancelPass `MediaIndexRunner.cancelRunningPass`: stops a running pass and waits for it.
 * @param closeSessions the backend's `closeSessions`.
 */
class AiReadiness(
    private val files: () -> FileDatabase,
    private val facts: () -> AiModelFacts,
    private val currentKey: () -> PreflightKey,
    private val storesUsable: () -> Boolean,
    private val applyFingerprint: suspend (RuntimeFingerprint) -> FingerprintOutcome,
    private val cancelPass: suspend () -> Unit,
    private val closeSessions: suspend () -> Unit,
    private val verdictPath: String = VERDICT_PATH,
    private val scenePath: String = SCENE_PATH,
    /** This process's loads in flight (§14.5). ⚠ A test's second ledger is a second process. */
    private val ledger: AttemptLedger = AttemptLedger.THIS_PROCESS,
) {
    /** ⚠⚠ Only a `Ready` snapshot holds a [model]: that is the whole of "only `Ready` serves". */
    private class Snapshot(
        val state: AiReadinessState,
        val model: PreparedModel? = null,
        val stoppedLoading: Boolean = false,
        /** ⚠ With [model]: the key it is served under, what a tower the backend opens is recorded against. */
        val key: PreflightKey? = null,
    )

    /** One reading of everything the state is derived from. */
    private class Reading(val facts: AiModelFacts, val key: PreflightKey, val verdict: PreflightVerdict?) {
        fun target(): Snapshot {
            if (!facts.installed) return Snapshot(AiReadinessState.NoModel)
            // ⚠⚠ A fail stands whether or not the towers are compiled: loads the process did not
            // survive are a fail of an install that may never have been marked (§14.5), and a
            // `Preparing` over it compiled and opened the towers again at every trigger.
            current()?.takeIf { it.passed == false }?.let { return Snapshot(AiReadinessState.Unsuitable, stoppedLoading = it.stoppedByInterruptedLoads) }
            val prepared: PreparedModel = facts.prepared ?: return Snapshot(AiReadinessState.Preparing)
            // ⚠⚠ Keyed on the whole key: a verdict for another archive, vocabulary, OS build,
            // build constant, app build or probe is no verdict.
            // ⚠ A record that holds attempts and no answer is no verdict either.
            val passed: Boolean = current()?.passed ?: return Snapshot(AiReadinessState.AwaitingVerdict)
            return if (passed) Snapshot(AiReadinessState.Ready, prepared, key = key) else Snapshot(AiReadinessState.Unsuitable)
        }

        /** The record on disk, when it is about what would run now. */
        fun current(): PreflightVerdict? = verdict?.takeIf { it.key == key }
    }

    /** This graph's lock: its state, and the order of its transitions. */
    private val lock = Mutex()

    /**
     * ⚠⚠ The process's lock of the verdict's file, not this graph's ([AttemptLedger.lockFor]):
     * taken inside [lock] by everything that reads or writes the file ([withLock]), so a retired
     * graph and its successor do not lose each other's read-modify-writes. ⚠ Not by
     * [unloadRetired], which touches no file and waits, with no deadline, for a prediction of its
     * own graph: holding this lock there kept every later graph from reading its own state.
     */
    private val fileLock: Mutex = ledger.lockFor(verdictPath)

    /** [lock], then [fileLock]: what every reader and writer of the file runs under. */
    private suspend inline fun <T> withLock(action: () -> T): T = lock.withLock { fileLock.withLock(action = action) }

    /** ⚠ What [serves] reads, on the per-image path: one volatile read, no file, no lock. */
    @Volatile
    private var snapshot: Snapshot = Snapshot(AiReadinessState.NoModel)

    /** ⚠ Read by [serving] before the snapshot, so [retire] stops serving without waiting for [lock]. */
    private val retirement = MutableStateFlow(false)
    private val retired: Boolean get() = retirement.value

    /**
     * Whether [retire] was called, for whoever shows this graph's state: a screen over a
     * retired graph says so, and does not go on showing the last thing it was told (§14.18).
     * ⚠ The one value [isRetired] and [serving] read; there is no second copy.
     */
    val retiredNow: StateFlow<Boolean> = retirement.asStateFlow()

    private val published = MutableStateFlow<AiReadinessState>(AiReadinessState.NoModel)

    /** The same state, for a screen. */
    val state: StateFlow<AiReadinessState> = published.asStateFlow()

    fun current(): AiReadinessState = snapshot.state

    /**
     * The model a call may run on now, or `null`. ⚠ The backend takes its paths from here and
     * from nowhere else, so it can never open a generation this state does not serve.
     */
    fun serving(): PreparedModel? = if (retired) null else snapshot.model?.takeIf { storesUsable() }

    /** What the backend's `supports` answers from. */
    fun serves(): Boolean = serving() != null

    /**
     * The owning runtime is being torn down. Terminal, and the half of a teardown that makes this
     * graph harmless: from here on nothing is served, whatever a later writer reads.
     *
     * ⚠⚠ Not a suspend function, and it waits for nothing: a teardown runs on the launch path,
     * on the main thread, and neither a writer that holds [lock] nor a prediction that holds a
     * tower may keep it there. [unloadRetired] is the other half.
     */
    fun retire() {
        retirement.value = true
    }

    /** Whether [retire] was called. What a preflight asks between its steps. */
    fun isRetired(): Boolean = retired

    /**
     * Finishes what [retire] began: the rest of the leaving sequence, the pass and then the
     * sessions. Memory, not safety: [retire] has already stopped everything this could serve.
     *
     * ⚠⚠ **No deadline, so never on a thread that must return.** Closing a tower waits for the
     * prediction that holds it, and a prediction is a blocking call into Core ML that nothing can
     * interrupt. The caller hands this to a scope that outlives the runtime being torn down.
     */
    suspend fun unloadRetired() {
        check(retired) { "unloadRetired() before retire()" }
        lock.withLock { leaveTo(Snapshot(AiReadinessState.NoModel)) }
    }

    /** Writer 1: the prepare step wrote a ready marker. */
    suspend fun markerWritten(): AiReadinessState = withLock { reconcile() }

    /**
     * Writer 1, as the prepare step uses it: the compiled cache is swept, a tower compiled or a
     * compiled tower discarded inside [change] (`ModelPreparer`).
     *
     * ⚠⚠ Around the change, like [storeChanging]: nothing is served from a cache that is
     * changing (a discard removes the directory a `Ready` model is loaded from), and what the
     * cache holds afterwards is read only once [change] has returned or thrown.
     * ⚠ Unlike [storeChanging] it forgets no failed probe: a model compiled a second time is the
     * same install, and its failures go on counting towards a failed one.
     * ⚠ [change] must not call back into this object.
     *
     * @return the state once the markers have been read.
     */
    suspend fun <T> compiledChanging(change: suspend () -> T): T = withLock {
        // ⚠⚠ Not on a retired readiness: its unload is [unloadRetired]'s, outside the file's lock,
        // and a stop here would wait on its own prediction while holding the process's file lock.
        if (snapshot.state == AiReadinessState.Ready && !retired) leaveTo(Snapshot(AiReadinessState.Preparing))
        try {
            change()
        } finally {
            withContext(NonCancellable) { reconcile() }
        }
    }

    /**
     * The compiled cache is being changed for the length of [change] (a compile is seconds):
     * **no probe begins meanwhile** ([awaitingProbe] answers that there is nothing to probe).
     *
     * ⚠⚠ Not held across [change]: this lock is taken to set the mark and to clear it, and a
     * probe that had already begun is the caller's to wait for inside [change], which it can,
     * because that probe needs this lock to record what it found. A probe marks itself as
     * holding its towers **inside** [awaitingProbe], under this lock, so the two orders are the
     * only two: the probe began first and is seen, or the mark was set first and no probe begins.
     */
    suspend fun <T> cacheChanging(change: suspend () -> T): T {
        lock.withLock { cacheBusyCount.value += 1 }
        try {
            return change()
        } finally {
            withContext(NonCancellable) { lock.withLock { cacheBusyCount.value -= 1 } }
        }
    }

    /** How many [cacheChanging] calls are in flight. Written under [lock]. */
    private val cacheBusyCount = MutableStateFlow(0)
    private val cacheBusy: Int get() = cacheBusyCount.value

    /**
     * How many changes of the compiled cache are in flight: a prepare, a discard, a sweep, an
     * eviction, **and the wait each makes first** for a probe or an earlier graph's unload.
     * ⚠⚠ Not *preparing now*: it is more than none at every check, whether or not anything is
     * compiled. A screen reads `ModelPreparer.compiling` for that.
     * ⚠ The very count [awaitingProbe] reads; nothing else keeps one.
     */
    val cacheChanges: StateFlow<Int> = cacheBusyCount.asStateFlow()

    /**
     * Writer 4: at launch and on every return to the foreground. A new OS build or build constant
     * changes the fingerprint, and the verdict held is then for something else.
     *
     * ⚠ It compares the verdict with the fingerprint this build would make **now**, not with an
     * `Applied` from the custody: the custody's own check has usually applied it already.
     */
    suspend fun fingerprintChecked(): AiReadinessState = withLock { reconcile() }

    /**
     * Writer 3: the model store commits or removes the checkpoint inside [change].
     *
     * ⚠⚠ Around the change, not after it: the store declares the old archive's files garbage the
     * moment it commits, so the old generation stops being served **before** the store moves, and
     * what the store holds afterwards is read only once [change] has returned or thrown.
     * ⚠ [change] must not call back into this object.
     */
    suspend fun <T> storeChanging(change: suspend () -> T): T = withLock {
        // ⚠⚠ As in [compiledChanging]: a retired graph stops nothing under the file's lock.
        if (snapshot.state == AiReadinessState.Ready && !retired) leaveTo(Snapshot(AiReadinessState.Preparing))
        try {
            change().also { forgetFailedProbes() }
        } finally {
            withContext(NonCancellable) { reconcile() }
        }
    }

    /**
     * ⚠ A commit or a removal that went through is a new install, and a count of probes the last
     * one failed says nothing about it. Without this a re-download of the same archive, which has
     * the same key, would still read as a failed install. The slow count stays: it is the phone's.
     */
    private fun forgetFailedProbes() {
        runCatching {
            // ⚠ Only over a record that was read: an unreadable one is left alone.
            val held: PreflightVerdict = (readVerdict() as? VerdictRead.Held)?.verdict
                ?.takeIf { it.passed == null && it.attempts.failed > 0 } ?: return
            write(held.copy(attempts = held.attempts.copy(failed = 0)))
        }.onFailure { println("AiReadiness -> failed probes not cleared: $it") }
    }

    /**
     * What the preflight may probe now, or `null`: only a prepared model with no verdict for the
     * current key, on a readiness that is not retired.
     *
     * ⚠ It reconciles first, under [lock]. So a model that was `Ready` a moment ago has been left
     * in order, its sessions closed, before the preflight opens its own pair beside nothing.
     */
    suspend fun awaitingProbe(holding: () -> Unit = {}): Awaiting = withLock {
        if (retired) return@withLock Awaiting.NothingToProbe
        // ⚠⚠ Not beside a prepare: see [cacheChanging].
        if (cacheBusy > 0) return@withLock Awaiting.NothingToProbe
        val now: Reading = read() ?: run {
            failClosed()
            return@withLock Awaiting.Unreadable
        }
        if (reconcile(now) != AiReadinessState.AwaitingVerdict) return@withLock Awaiting.NothingToProbe
        val prepared: PreparedModel = now.facts.prepared ?: return@withLock Awaiting.NothingToProbe
        // ⚠⚠ Under this lock, so whoever marks the cache as changing either sees it or came first.
        holding()
        Awaiting.Probe(ProbeTarget(now.key, prepared, now.current()?.attempts ?: PreflightAttempts.NONE))
    }

    /**
     * Writer 2, as the preflight calls it: one probe of [probed] ended as [result].
     *
     * ⚠⚠ **One call, one lock, for the count and the answer it may amount to.** The third slow
     * probe is counted and made `Unsuitable` here, together. Counting in one call and deciding in
     * a second would leave a gap in which the store could commit a replacement: the verdict would
     * then be dropped, rightly, and the caller would already have concluded `Unsuitable` from the
     * count.
     *
     * ⚠ Dropped, and said to be dropped, when [probed] is no longer what would run, when there
     * is an answer already, and on a retired readiness. A miss that cannot be written is not
     * counted: the probe is then simply run again.
     */
    suspend fun recordProbe(result: ProbeResult, probed: PreflightKey, atEpochMs: Long): ProbeRecorded = withLock {
        if (retired) return@withLock ProbeRecorded.Dropped(snapshot.state)
        val now: Reading = read() ?: return@withLock ProbeRecorded.Dropped(failClosed())
        val prepared: PreparedModel? = now.facts.prepared
        if (!now.facts.installed || prepared == null || probed != now.key) {
            return@withLock ProbeRecorded.Dropped(reconcile(now))
        }
        val held: PreflightVerdict? = now.current()
        // ⚠ Never over an answer: the probe began before it, and says nothing new.
        if (held?.passed != null) return@withLock ProbeRecorded.Dropped(reconcile(now))
        val before: PreflightAttempts = held?.attempts ?: PreflightAttempts.NONE
        if (result == ProbeResult.PASSED) {
            val state: AiReadinessState = decide(prepared, PreflightVerdict(probed, passed = true, attempts = before))
            // ⚠ A pass whose fingerprint could not be applied, or which could not be persisted,
            // is not `Ready` and is not reported as one.
            return@withLock if (state == AiReadinessState.Ready) ProbeRecorded.Decided(true, state, before) else ProbeRecorded.Dropped(state)
        }
        val after: PreflightAttempts = when (result) {
            ProbeResult.SLOW -> before.copy(slow = before.slow + 1)
            ProbeResult.FAILED -> before.copy(failed = before.failed + 1)
            else -> before.copy(transient = before.transient + 1)
        }.copy(lastAtEpochMs = atEpochMs)
        if (result == ProbeResult.SLOW && after.slow >= PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE) {
            val state: AiReadinessState = decide(prepared, PreflightVerdict(probed, passed = false, attempts = after))
            // ⚠ As for a pass, and as for a count: a verdict that did not reach the disk was not
            // decided. Neither the third count nor the answer exists, here or at the next launch.
            return@withLock if (state == AiReadinessState.Unsuitable) ProbeRecorded.Decided(false, state, after) else ProbeRecorded.Dropped(state)
        }
        runCatching { write(PreflightVerdict(probed, passed = null, attempts = after)) }
            .onFailure { println("AiReadiness -> probe attempt not persisted: $it") }
            .getOrNull() ?: return@withLock ProbeRecorded.Dropped(snapshot.state)
        ProbeRecorded.Counted(after)
    }

    /**
     * Writer 2's verdict, bare: an answer about [probed], with no probe counted.
     *
     * ⚠ The preflight does not call this. It calls [recordProbe], which counts and decides under
     * one lock and says what it did. This is the same decision for a caller that already has one.
     *
     * ⚠ An answer about something that is no longer current is dropped: the probe ran on sessions
     * it opened itself, and the store or the OS may have moved under it.
     */
    suspend fun recordVerdict(passed: Boolean, probed: PreflightKey): AiReadinessState = withLock {
        if (retired) return@withLock snapshot.state
        val now: Reading = read() ?: return@withLock failClosed()
        val prepared: PreparedModel? = now.facts.prepared
        if (!now.facts.installed || prepared == null || probed != now.key) return@withLock reconcile(now)
        // The counts stay beside the answer they led to: the Models screen says why.
        decide(prepared, PreflightVerdict(probed, passed, now.current()?.attempts ?: PreflightAttempts.NONE))
    }

    /**
     * The scene changed: [active] or not, and when it left, the foreground it left
     * ([leftForeground]). Writes this process's [SceneLeft] at once, on the caller's thread: one
     * small atomic write of its own file, with no lock (only this process's scene changes write
     * it, and they arrive on one thread). ⚠⚠ It must reach the disk while the process is still
     * alive, which it is when the scene changes, and before iOS suspends it.
     *
     * ⚠⚠ Beside this process's record it keeps the one of the process whose load the verdict's
     * file holds ([SceneRecords]), read without the lock: whichever way that read and a settle
     * interleave, the settle has read the scene before it clears the load (its write comes after
     * its read), so a record is never dropped before it was read. ⚠ A verdict that cannot be read
     * keeps every record (up to [SCENE_RECORDS_KEPT], newest last); a scene file that cannot be
     * read keeps none, and an earlier process's frozen load is then counted as a kill: the
     * conservative side, as for a write that fails on leaving.
     * ⚠ A write that fails is tried once more. Failing on **coming back** leaves the record of the
     * foreground that was left, so a load begun before it left and killed after it came back is
     * counted as frozen (transient), not as a kill: the unsafe side, bounded to that one load.
     */
    fun sceneChanged(active: Boolean, leftForeground: Long?) {
        // ⚠⚠ Leaving is idempotent: a second leave names no foreground (`applicationWillTerminate`
        // after `.background`), and writing it would erase the foreground the first one recorded,
        // so every load still in flight in the window would count as a kill.
        if (!active && leftForeground == null) return
        val mine = SceneLeft(ledger.process, leftForeground = if (active) null else leftForeground)
        val others: List<SceneLeft> = when (val held: SceneRead = readScene()) {
            SceneRead.None, SceneRead.Unreadable -> emptyList()
            is SceneRead.Held -> stillOwed(held.scenes.filter { it.process != mine.process })
        }
        val bytes: ByteArray = json.encodeToString(SceneRecords.serializer(), SceneRecords(others + mine)).encodeToByteArray()
        val written: Boolean = (1..2).any { runCatching { files().writeRawByteArray(scenePath, bytes) }.isSuccess }
        if (!written) println("AiReadiness -> the scene's record not written")
    }

    /**
     * Of [foreign], the records a settle may still read: those of the processes whose load the
     * verdict's file or a tower's open record holds ([servingOpen]), or, when any of them cannot
     * be read, the newest of them. ⚠ Read without the locks: a settle reads the scene before it
     * clears a record, so either interleaving keeps what it needs.
     * ⚠ Its own function, and the verdict's read an inline `try` whose catch RETURNS rather than
     * defaulting to a sibling `VerdictRead`: K/N 2.4.10 miscompiles a type test of a value
     * defaulted inline to a sibling (`tech-debt.md`), so no such default may reach the `when`.
     */
    private fun stillOwed(foreign: List<SceneLeft>): List<SceneLeft> {
        val verdict: VerdictRead = try {
            readVerdict()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return foreign.takeLast(SCENE_RECORDS_KEPT - 1)
        }
        val owed: MutableSet<String> = when (verdict) {
            VerdictRead.Unreadable -> return foreign.takeLast(SCENE_RECORDS_KEPT - 1)
            VerdictRead.None -> mutableSetOf()
            is VerdictRead.Held -> listOfNotNull(verdict.verdict.begun?.process).toMutableSet()
        }
        for (role in OPEN_ROLES) {
            when (val open: OpenRead = openOf(role)) {
                OpenRead.None -> Unit
                OpenRead.Unreadable -> return foreign.takeLast(SCENE_RECORDS_KEPT - 1)
                is OpenRead.Held -> owed += open.begun.process
            }
        }
        return foreign.filter { it.process in owed }
    }

    /** ⚠ Its own function, for the reason [stillOwed] gives. */
    private fun openOf(role: String): OpenRead = try {
        readOpen(openPath(role))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        OpenRead.Unreadable
    }

    private sealed interface SceneRead {
        data object None : SceneRead
        class Held(val scenes: List<SceneLeft>) : SceneRead
        data object Unreadable : SceneRead
    }

    private fun readScene(): SceneRead {
        val database: FileDatabase = files()
        val bytes: ByteArray? = try {
            database.readBytes(scenePath)?.get()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return SceneRead.Unreadable
        }
        if (bytes == null) {
            val present: Boolean = try {
                database.fileSize(scenePath) != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return SceneRead.Unreadable
            }
            return if (present) SceneRead.Unreadable else SceneRead.None
        }
        val text: String = bytes.decodeToString()
        // ⚠ A one-record file, as older builds wrote it, is read as one record; bytes that decode
        // as neither are another build's: no record of a scene that left.
        val decoded: List<SceneLeft> = runCatching { json.decodeFromString(SceneRecords.serializer(), text).left }.getOrNull()
            ?: runCatching { listOf(json.decodeFromString(SceneLeft.serializer(), text)) }.getOrNull()
            ?: return SceneRead.None
        return SceneRead.Held(decoded)
    }

    /** What the verdict's file says of [key], for a model not installed (its row, §14.5). */
    sealed interface KeyAnswer {
        data object Unreadable : KeyAnswer

        /** No fail for [key]: a download of it is offered. */
        data object Open : KeyAnswer

        /** A fail for [key]. [stoppedLoading]: by interrupted loads alone ([PreflightVerdict.stoppedByInterruptedLoads]). */
        data class Unsuitable(val stoppedLoading: Boolean) : KeyAnswer
    }

    /**
     * Whether [key] — computed from a catalog entry's digests and this OS build, for a model that
     * is not installed — is answered already. ⚠⚠ So that a 743 MB download is not offered that the
     * key would answer `Unsuitable` at once.
     */
    suspend fun answerFor(key: PreflightKey): KeyAnswer = withLock {
        val now: Reading = read() ?: return@withLock KeyAnswer.Unreadable
        val held: PreflightVerdict = now.verdict?.takeIf { it.key == key && it.passed == false } ?: return@withLock KeyAnswer.Open
        KeyAnswer.Unsuitable(held.stoppedByInterruptedLoads)
    }

    /**
     * ⚠⚠ **The one way out of an `Unsuitable` that interrupted loads made** (§14.5 item 0), asked
     * by the user: the fail is withdrawn and one more load is allowed, so the next interrupted
     * load marks it again. ⚠ Only that fail: three slow probes keep D12's rule.
     * @return whether anything was withdrawn. ⚠ Written before it is believed.
     */
    suspend fun loadAgain(key: PreflightKey): Boolean = withLock {
        if (retired) return@withLock false
        val now: Reading = read() ?: return@withLock false
        val held: PreflightVerdict = now.verdict?.takeIf { it.key == key && it.stoppedByInterruptedLoads } ?: return@withLock false
        val again = held.copy(
            passed = null,
            attempts = held.attempts.copy(interrupted = PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE - 1, lastAtEpochMs = 0),
        )
        val written: Boolean = runCatching { write(again) }.onFailure { println("AiReadiness -> not loaded again, not written: $it") }.isSuccess
        if (written) reconcile()
        written
    }

    /** What [loadGate] answers. */
    enum class LoadGate {
        /** Load: nothing on disk answers this install, and its record could be read. */
        GO,

        /** The verdict's file could not be read: a load begun now could not be counted. */
        UNREADABLE,

        /** A fail stands for this install ([AiReadinessState.Unsuitable]): nothing of it is loaded again. */
        ANSWERED,
    }

    /**
     * Whether the prepare step may compile and first-open towers now (§14.5), asked before it
     * compiles anything. ⚠⚠ The prepare had no stop of its own: a first open that killed Photos
     * was compiled and opened again at every trigger, and a compile only to learn at its first
     * open that the open cannot be counted is 1.8 GB of writes at every foreground.
     * ⚠ A retired readiness answers [LoadGate.GO]: the preparer's own question stops it.
     */
    suspend fun loadGate(): LoadGate = withLock {
        if (retired) return@withLock LoadGate.GO
        val now: Reading = read() ?: run {
            failClosed()
            return@withLock LoadGate.UNREADABLE
        }
        if (reconcile(now) == AiReadinessState.Unsuitable) LoadGate.ANSWERED else LoadGate.GO
    }

    /**
     * Runs [load] — a probe, or one tower's first open — **with its [AttemptBegun] on disk for
     * its whole length** (D12, §14.5).
     *
     * ⚠⚠ In this order: the record is written, under [lock], before [load] starts, and [load] does
     * not start unless it was; the record is cleared, on every way out of [load] that this process
     * lives through, after [load] has ended. A recorded outcome ([recordProbe]) replaces it in the
     * same write. So a record a later process finds is a load nobody lived through, and a stop
     * (Photos left the foreground, a teardown, a failure) leaves none.
     * ⚠ [lock] is not held across [load]: [load] records its own outcome under it.
     *
     * @param key what would run now, the install the load is of: the record carries it, and is
     *   counted against it and nothing else.
     * @param foreground which foreground of the scene the load begins in ([SceneLeft]).
     * @throws AttemptNotRecordedException when the record could not be written first: nothing ran.
     */
    suspend fun <T> attempting(kind: AttemptKind, role: String?, key: PreflightKey, atEpochMs: Long, foreground: Long?, load: suspend () -> T): T {
        val serial: Long = ledger.open()
        val begun = AttemptBegun(key, kind, role, ledger.process, serial, atEpochMs, foreground)
        try {
            withLock { begin(begun) }
        } catch (failure: Throwable) {
            ledger.close(serial)
            throw failure
        }
        try {
            return load()
        } finally {
            withContext(NonCancellable) { withLock { end(begun) } }
            // ⚠ After the disk: while it is still on disk, this process is still loading.
            ledger.close(serial)
        }
    }

    /** Under [lock]. @throws AttemptNotRecordedException */
    private suspend fun begin(begun: AttemptBegun) {
        if (retired) throw AttemptNotRecordedException(AttemptNotRecordedException.Why.RETIRED, "retired")
        val now: Reading = read() ?: run {
            failClosed()
            throw AttemptNotRecordedException(AttemptNotRecordedException.Why.UNREADABLE, "the verdict's file could not be read")
        }
        if (now.key != begun.key) throw AttemptNotRecordedException(AttemptNotRecordedException.Why.MOVED, "the install moved under the load")
        if (now.current()?.passed == false) {
            reconcile(now)
            throw AttemptNotRecordedException(AttemptNotRecordedException.Why.MOVED, "the install has its answer")
        }
        // ⚠ Beside whatever the file holds, a record about another key included: a load that
        // ends with no outcome leaves the file as it found it (a delete is not trusted to
        // delete: `FileDatabase.deleteFile` is a no-op on some platforms).
        val marked: PreflightVerdict = (now.verdict ?: PreflightVerdict(begun.key, passed = null, attempts = PreflightAttempts.NONE)).copy(begun = begun)
        try {
            write(marked)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw AttemptNotRecordedException(AttemptNotRecordedException.Why.UNREADABLE, "the load's record could not be written: $failure")
        }
    }

    /**
     * Under [lock]. Clears [begun] when the file still holds exactly it: an outcome written
     * since replaced it already. ⚠ Also on a retired readiness: a teardown is a stop, and a stop
     * leaves no record to be counted as a kill by the next process.
     * ⚠ A clear that fails leaves the record: then this process's next read clears it (the ledger
     * says it ended), or the next process counts it. Never the other way round.
     */
    private fun end(begun: AttemptBegun) {
        val held: PreflightVerdict = (readVerdict() as? VerdictRead.Held)?.verdict ?: return
        if (held.begun != begun) return
        runCatching { write(held.copy(begun = null)) }.onFailure { println("AiReadiness -> a load's record not cleared: $it") }
    }

    /**
     * Runs [open] — one tower the backend opens while `Ready` — **with its [AttemptBegun] on disk
     * for its whole length** (§14.19, closing §14.18.16 item 9): a search's, the warm-up's and an
     * index pass's opens are counted like a probe's when Photos does not survive them, so a phone
     * killed only by them stops loading at the second as it would at a probe.
     *
     * ⚠⚠ **Its tower's own file, never the verdict's, and never under readiness's lock.** The
     * backend calls this under its tower's lock, and a writer that stops serving holds
     * readiness's lock and the verdict file's while it waits for that tower (`closeSessions`): a
     * record taken under either would close the cycle. The file's own lock
     * ([AttemptLedger.lockFor]) is held for each write only, and its holder waits for nothing.
     * ⚠ Non-suspending [open] by design: it is one blocking Core ML call.
     *
     * @throws AttemptNotRecordedException when nothing is served, or the record could not be
     *   written first: nothing was opened.
     */
    suspend fun <T> servingOpen(role: String, foreground: Long?, atEpochMs: Long, open: () -> T): T {
        val serving: Snapshot = snapshot
        val key: PreflightKey = serving.key?.takeIf { serving.state == AiReadinessState.Ready && !retired }
            ?: throw AttemptNotRecordedException(AttemptNotRecordedException.Why.MOVED, "nothing is served")
        val serial: Long = ledger.open()
        val begun = AttemptBegun(key, AttemptKind.SERVE_OPEN, role, ledger.process, serial, atEpochMs, foreground)
        val path: String = openPath(role)
        val fileLock: Mutex = ledger.lockFor(path)
        try {
            fileLock.withLock {
                if (!writeTwice(path, encodeOpen(begun))) {
                    throw AttemptNotRecordedException(AttemptNotRecordedException.Why.UNREADABLE, "the open's record could not be written")
                }
            }
        } catch (failure: Throwable) {
            ledger.close(serial)
            throw failure
        }
        try {
            return open()
        } finally {
            withContext(NonCancellable) {
                // ⚠ A clear that fails leaves the record: this process's next open of the tower
                // writes over it, or the next process counts it — a kill too many, never one too few.
                fileLock.withLock { if (!writeTwice(path, encodeOpen(null))) println("AiReadiness -> an open's record not cleared ($role)") }
            }
            ledger.close(serial)
        }
    }

    /**
     * [held] with every tower open the backend began and its process did not survive counted:
     * **one load per dead process**, however many towers it was opening.
     *
     * ⚠⚠ The towers open under separate locks on separate dispatchers, so a process can die with
     * both records on disk — and loading both at once is the peak of memory, the likeliest moment
     * of a jetsam. Counted per file, that one kill would be two interrupted loads, which is
     * `Unsuitable` after a single jetsam. So the records are collected
     * from every tower's file first, one counted per process (its earliest time, and the latest
     * foreground it opened in: an open begun in a foreground the scene never left was a kill in
     * front of the user, whatever the other tower's says), then every counted record cleared.
     */
    private suspend fun settledOpens(held: PreflightVerdict?, key: PreflightKey): PreflightVerdict? {
        val dead: MutableList<Pair<String, AttemptBegun>> = mutableListOf()
        for (role in OPEN_ROLES) {
            val path: String = openPath(role)
            ledger.lockFor(path).withLock {
                // ⚠ An unreadable record is not *no open*: nothing is concluded, and nothing loaded.
                val begun: AttemptBegun = when (val read: OpenRead = readOpen(path)) {
                    OpenRead.None -> return@withLock
                    OpenRead.Unreadable -> throw IllegalStateException("the record of a tower's open could not be read ($role)")
                    is OpenRead.Held -> read.begun
                }
                // ⚠ This process's is its own to clear (it may still be opening); another key's
                // is no count of this one, and the next open of the tower writes over it.
                if (begun.process == ledger.process || begun.key != key) return@withLock
                dead += role to begun
            }
        }
        if (dead.isEmpty()) return held
        var now: PreflightVerdict? = held
        for (opens: List<AttemptBegun> in dead.groupBy({ it.second.process }, { it.second }).values) {
            now = counted(now, oneLoad(opens), key)
        }
        val counted: PreflightVerdict = now ?: return held
        // ⚠⚠ Written before it is believed, then the records cleared: a clear that fails counts
        // it again at the next read (a kill too many, never one too few).
        write(counted)
        for ((role, begun) in dead) {
            val path: String = openPath(role)
            ledger.lockFor(path).withLock {
                // ⚠ Only the record counted: this process's own open of the tower may have written
                // over it since the lock was let go, and that one is still running.
                if (readOpen(path).heldBegun() != begun) return@withLock
                if (!writeTwice(path, encodeOpen(null))) println("AiReadiness -> a counted open's record not cleared ($role)")
            }
        }
        return counted
    }

    /** The one load [opens] — one dead process's open records, one per tower — are counted as. */
    private fun oneLoad(opens: List<AttemptBegun>): AttemptBegun {
        val earliest: AttemptBegun = opens.minBy { it.atEpochMs }
        val foreground: Long? = opens.mapNotNull { it.foreground }.maxOrNull()
        return earliest.copy(foreground = foreground)
    }

    private fun OpenRead.heldBegun(): AttemptBegun? = when (this) {
        OpenRead.None, OpenRead.Unreadable -> null
        is OpenRead.Held -> begun
    }

    private sealed interface OpenRead {
        data object None : OpenRead
        class Held(val begun: AttemptBegun) : OpenRead
        data object Unreadable : OpenRead
    }

    private fun readOpen(path: String): OpenRead {
        val database: FileDatabase = files()
        val bytes: ByteArray? = try {
            database.readBytes(path)?.get()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return OpenRead.Unreadable
        }
        if (bytes == null) {
            val present: Boolean = try {
                database.fileSize(path) != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return OpenRead.Unreadable
            }
            return if (present) OpenRead.Unreadable else OpenRead.None
        }
        // ⚠ Cleared, or bytes another build wrote: no open.
        val begun: AttemptBegun = runCatching { json.decodeFromString(OpenRecord.serializer(), bytes.decodeToString()).begun }.getOrNull()
            ?: return OpenRead.None
        return OpenRead.Held(begun)
    }

    private fun encodeOpen(begun: AttemptBegun?): ByteArray = json.encodeToString(OpenRecord.serializer(), OpenRecord(begun)).encodeToByteArray()

    private fun writeTwice(path: String, bytes: ByteArray): Boolean = (1..2).any { runCatching { files().writeRawByteArray(path, bytes) }.isSuccess }

    private fun openPath(role: String): String = "$verdictPath.open.$role"

    /**
     * [held] with a load that **its process did not survive** counted (§14.5): what every reading
     * of the verdict's file goes through.
     *
     * ⚠⚠ Written before it is believed: a count that cannot be written throws, and the reading is
     * unreadable; memory is never a kill ahead of the disk, and nothing is loaded meanwhile.
     * ⚠ A load of another key (an earlier install, OS build or revision) is not counted against
     * this one; the next write replaces it.
     * ⚠ A record of THIS process that is not running ended without its clear reaching the disk
     * (a failed write): it is cleared, not counted.
     */
    private fun settled(held: PreflightVerdict, key: PreflightKey): PreflightVerdict {
        val begun: AttemptBegun = held.begun ?: return held
        if (begun.process == ledger.process) {
            if (ledger.isRunning(begun.serial)) return held
            val cleared: PreflightVerdict = held.copy(begun = null)
            return if (runCatching { write(cleared) }.isSuccess) cleared else held
        }
        if (begun.key != key) return held
        val next: PreflightVerdict = counted(held, begun, key).copy(begun = null)
        write(next)
        return next
    }

    /**
     * [held] with [begun], a load of [key] that its process did not survive, counted: transient
     * when the scene had left the foreground it began in, else interrupted, and `Unsuitable` at
     * the bound. ⚠ Keeps whatever load [held] itself records. Writes nothing.
     */
    private fun counted(held: PreflightVerdict?, begun: AttemptBegun, key: PreflightKey): PreflightVerdict {
        // ⚠ The record of this key, or a new one: a record of another key is no count of this one.
        val base: PreflightAttempts = held?.takeIf { it.key == key }?.attempts ?: PreflightAttempts.NONE
        // ⚠⚠ Frozen by iOS when Photos left the foreground, and ended there: not a kill in front
        // of the user, and never a step towards `Unsuitable`. Transient, so the backoff holds.
        // A scene file that cannot be read throws: nothing is concluded from a failed read.
        val foreground: Long? = begun.foreground
        val frozen: Boolean = foreground == null || when (val left: SceneRead = readScene()) {
            SceneRead.Unreadable -> throw IllegalStateException("the scene's record could not be read")
            SceneRead.None -> false
            is SceneRead.Held -> left.scenes.lastOrNull { it.process == begun.process }?.leftForeground.let { it != null && foreground <= it }
        }
        val counts: PreflightAttempts = if (frozen) {
            base.copy(transient = base.transient + 1, lastAtEpochMs = maxOf(base.lastAtEpochMs, begun.atEpochMs))
        } else {
            base.copy(interrupted = base.interrupted + 1, lastAtEpochMs = maxOf(base.lastAtEpochMs, begun.atEpochMs))
        }
        val answered: Boolean = !frozen && counts.interrupted >= PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE
        val passed: Boolean? = if (answered) false else held?.takeIf { it.key == key }?.passed
        println(
            "AiReadiness -> a ${begun.kind} load (${begun.role ?: "both towers"}) did not end" +
                if (frozen) " after Photos left the foreground: transient" else ": ${counts.interrupted} so far${if (answered) ", unsuitable" else ""}",
        )
        return PreflightVerdict(key, passed = passed, attempts = counts, begun = held?.begun)
    }

    /**
     * Applies [verdict], which is about what would run now. Under [lock].
     *
     * ⚠⚠ **Two rules, in opposite directions, and they are not to be confused.**
     * - **An answer is written before it is believed.** `Ready` and `Unsuitable` are published
     *   only after the verdict is on disk. A write that fails leaves the state without an answer
     *   (`AwaitingVerdict`) and the file as it was, so memory never says what the next launch
     *   would not, and the caller is told nothing was decided.
     * - **Serving stops before anything is written.** Leaving `Ready` flips first, and stays
     *   flipped whatever the write then does: a model is never served on the strength of a
     *   write that might succeed.
     *
     * @return the state reached. The verdict was applied only when it is the verdict's own.
     */
    private suspend fun decide(prepared: PreparedModel, verdict: PreflightVerdict): AiReadinessState {
        if (verdict.passed != true) {
            val wasReady: Boolean = snapshot.state == AiReadinessState.Ready
            // Not served from here, whatever follows.
            if (wasReady) publish(Snapshot(AiReadinessState.AwaitingVerdict))
            try {
                val written: Boolean = runCatching { write(verdict) }
                    .onFailure { println("AiReadiness -> not unsuitable, the verdict could not be persisted: $it") }
                    .isSuccess
                // ⚠ On disk first. Unwritten, the phone has no verdict here either: the state is
                // what a relaunch would read, and the probe is simply run again.
                if (written) publish(Snapshot(AiReadinessState.Unsuitable, stoppedLoading = verdict.stoppedByInterruptedLoads))
            } finally {
                if (wasReady) stopServing()
            }
            return snapshot.state
        }
        if (snapshot.state == AiReadinessState.Ready && snapshot.model == prepared) return snapshot.state
        leaveTo(Snapshot(AiReadinessState.AwaitingVerdict))
        enter(prepared, verdict.key, persist = verdict)
        return snapshot.state
    }

    private val stood = MutableStateFlow<ReadinessStanding>(ReadinessStanding.NotReadYet)

    /**
     * [state] **with the install it is about**, or that nothing was read. What a screen joins
     * with what it read from the store (§14.18): see [ReadinessStanding].
     * ⚠ Written in the same write as [state], under [lock] ([publish]), and by [reconcile].
     */
    val standing: StateFlow<ReadinessStanding> = stood.asStateFlow()

    /** The key of the reading last reconciled or entered. Under [lock]. */
    private var about: PreflightKey? = null

    /** Under [lock]: over a fresh reading. */
    private suspend fun reconcile(): AiReadinessState = reconcile(if (retired) null else read())

    /** Under [lock]. */
    private suspend fun reconcile(reading: Reading?): AiReadinessState {
        if (retired) return snapshot.state
        // ⚠ Before anything below publishes: what it publishes is about THIS reading.
        if (reading != null) about = reading.key
        return reconciled(reading).also { state ->
            // ⚠ After the state it describes, and also when the state did not move: the key may have.
            stood.value = if (reading == null) {
                ReadinessStanding.Unreadable
            } else {
                ReadinessStanding.For(reading.key, state, stoppedLoading = state == AiReadinessState.Unsuitable && snapshot.stoppedLoading)
            }
        }
    }

    /** Under [lock]. */
    private suspend fun reconciled(reading: Reading?): AiReadinessState {
        val now: Reading = reading ?: return failClosed()
        val target: Snapshot = now.target()
        val held: Snapshot = snapshot
        if (held.state == target.state && held.model == target.model) {
            // ⚠ `Ready` over stores the custody has closed (a realm reset, a failed clear, a
            // directory that lost its backup exclusion): the model is still the right one, and
            // nothing may use it. The backend closes a session only when it is next called, so
            // without this the towers stay loaded for as long as nobody calls.
            if (held.model != null && !storesUsable()) stopServing()
            // ⚠ The same state for another reason (loads interrupted, then slow probes): said.
            if (held.stoppedLoading != target.stoppedLoading) publish(target)
            return held.state
        }
        val model: PreparedModel? = target.model
        if (target.state != AiReadinessState.Ready || model == null) {
            leaveTo(target)
            return snapshot.state
        }
        // Another generation, or the first: never straight from one model to the next.
        leaveTo(Snapshot(AiReadinessState.AwaitingVerdict))
        enter(model, now.key, persist = null)
        return snapshot.state
    }

    /** What cannot be read cannot be served. Under [lock]. */
    private suspend fun failClosed(): AiReadinessState {
        if (snapshot.state == AiReadinessState.Ready) leaveTo(Snapshot(AiReadinessState.AwaitingVerdict))
        return snapshot.state
    }

    /** ⚠⚠ In this order: not served first, then the pass, then the sessions. Under [lock]. */
    private suspend fun leaveTo(next: Snapshot) {
        val wasReady: Boolean = snapshot.state == AiReadinessState.Ready
        publish(next)
        if (wasReady) stopServing()
    }

    private suspend fun stopServing() {
        try {
            cancelPass()
        } finally {
            // ⚠ Whatever the cancel did: a session left open is the old model, still loaded.
            withContext(NonCancellable) { closeSessions() }
        }
    }

    /**
     * ⚠⚠ In this order: the fingerprint, then the verdict, then the flip. A step that fails
     * leaves the state where it was, which is not `Ready`. Under [lock].
     */
    private suspend fun enter(model: PreparedModel, key: PreflightKey, persist: PreflightVerdict?) {
        about = key
        // ⚠ The custody is handed its own half and nothing else: a vocabulary, an app build or a
        // probe that changed is no reason to clear a vector.
        val applied: FingerprintOutcome = applyFingerprint(key.runtime)
        if (applied is FingerprintOutcome.Failed) {
            println("AiReadiness -> not ready, the fingerprint could not be applied: ${applied.cause}")
            return
        }
        if (persist != null) {
            try {
                write(persist)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                println("AiReadiness -> not ready, the verdict could not be persisted: $failure")
                return
            }
        }
        publish(Snapshot(AiReadinessState.Ready, model, key = key))
    }

    private fun publish(next: Snapshot) {
        snapshot = next
        // ⚠⚠ The same write, and FIRST: a state is never published without what it is about,
        // and a collector that runs inside the setter below already finds the standing moved.
        about?.let { stood.value = ReadinessStanding.For(it, next.state, next.stoppedLoading) }
        published.value = next.state
    }

    /**
     * One reading of everything the state is derived from, or `null` when **any** of it could
     * not be read.
     *
     * ⚠⚠ `null` is *unreadable*, never *empty*. Every caller stops there: a state that was served
     * is not served ([failClosed]), a probe's result is dropped, and **nothing is written**. A
     * record assumed empty after a failed read would be written back over a pass or over counts
     * that are still on disk.
     */
    private suspend fun read(): Reading? = try {
        val facts: AiModelFacts = facts()
        val key: PreflightKey = currentKey()
        when (val verdict: VerdictRead = readVerdict()) {
            VerdictRead.Unreadable -> null
            VerdictRead.None -> Reading(facts, key, settledOpens(null, key))
            is VerdictRead.Held -> Reading(facts, key, settledOpens(settled(verdict.verdict, key), key))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        println("AiReadiness -> the model's state could not be read: $failure")
        null
    }

    /** What the verdict file holds, with the two ways of holding nothing kept apart. */
    private sealed interface VerdictRead {
        /**
         * No verdict: there is no file, or there is one that is not this build's record (empty,
         * not JSON, another shape of the key, revision 1's `{key, passed}`).
         *
         * ⚠ The second half is a decision, made here and for this file only: bytes that were
         * read and do not decode can never become a verdict, so there is nothing in them to
         * protect, and the next result is written over them. It is **not** an unreadable file.
         */
        data object None : VerdictRead

        class Held(val verdict: PreflightVerdict) : VerdictRead

        /** The file is there and its bytes could not be had. What it holds is unknown. */
        data object Unreadable : VerdictRead
    }

    private fun readVerdict(): VerdictRead {
        val database: FileDatabase = files()
        val bytes: ByteArray? = try {
            database.readBytes(verdictPath)?.get()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("AiReadiness -> the verdict could not be read: $failure")
            return VerdictRead.Unreadable
        }
        if (bytes == null) {
            // ⚠⚠ `null` is not yet *absent*. A `FileDatabase` may answer `null` for a file it
            // could not read as for one that is not there (`IosFileDatabase.readBytes` does:
            // `NSData.dataWithContentsOfFile` is `nil` either way). So the file's presence is
            // asked separately, and a file that is there with no bytes to give is unreadable.
            val present: Boolean = try {
                database.fileSize(verdictPath) != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return VerdictRead.Unreadable
            }
            return if (present) VerdictRead.Unreadable else VerdictRead.None
        }
        val decoded: PreflightVerdict? = bytes.decodeToString().takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(PreflightVerdict.serializer(), it) }.getOrNull() }
        return if (decoded == null) VerdictRead.None else VerdictRead.Held(decoded)
    }

    private fun write(verdict: PreflightVerdict) {
        files().writeRawByteArray(verdictPath, json.encodeToString(PreflightVerdict.serializer(), verdict).encodeToByteArray())
    }

    companion object {
        /** Beside the realm stamp and the fingerprint sidecar, in the owner's private database. */
        const val VERDICT_PATH: String = "/inference/preflight.verdict"

        /** [SceneRecords], beside the verdict. */
        const val SCENE_PATH: String = "/inference/preflight.scene"

        /** The towers whose opens are recorded ([servingOpen]): a file each. */
        val OPEN_ROLES: List<String> = listOf("vision", "text")

        /** How many records a scene file keeps while the verdict cannot be read. */
        internal const val SCENE_RECORDS_KEPT: Int = 8

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** One tower's open in flight ([AiReadiness.servingOpen]), or none: `begun = null` is a cleared record. */
@Serializable
internal data class OpenRecord(val begun: AttemptBegun?)
