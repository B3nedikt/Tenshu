package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.onnx.VisionModel
import kotlin.time.ExperimentalTime

/**
 * One compiled tower, as the cache holds it. ⚠⚠ Three answers and no accessor that folds two of
 * them together: a marker that is there and cannot be read is not a tower that was never
 * compiled, and compiling over it would be deleting on the strength of a read that failed.
 */
sealed interface CompiledTower {
    /** Its ready marker names the digest asked for, and the compiled directory is there. */
    data class Ready(val path: String) : CompiledTower

    /** No marker, a marker for another digest, or no directory: to be compiled. */
    data object Absent : CompiledTower

    /** The marker is there and its bytes could not be had. Nothing is known. */
    data class Unreadable(val why: String) : CompiledTower
}

/** What a sweep of the compiled cache did. ⚠ *Could not list* is not *nothing to remove*. */
sealed interface CompiledSweep {
    /** @param left what was to go and **is still there**: a delete that failed is not a delete. */
    data class Swept(val removed: List<String>, val left: List<String> = emptyList()) : CompiledSweep

    data class Unlisted(val why: String) : CompiledSweep
}

/**
 * The compiled cache, as [ModelPreparer] needs it: [CompiledCache] over the platform's
 * [CompiledFiles] (`ios-on-device-inference.md` §3.4, §14.17).
 */
interface CompiledModels {
    fun tower(role: String, archiveDigest: String): CompiledTower

    /**
     * Archive → compiled directory → first open → marker, the marker last.
     *
     * @param proceed asked **before each step**, the marker's included. It throws to stop, and
     *   then nothing of this tower is left: no marker, no directory, no compiler output.
     * @param loads around the tower's compile and its first open: the loads recorded before they begin (§14.5).
     * @throws Exception when a step fails; and then no marker exists.
     */
    suspend fun prepare(role: String, archivePath: String, archiveDigest: String, proceed: () -> Unit, loads: LoadRecording)

    /**
     * Deletes the marker of [archiveDigest], then its compiled directory.
     * @throws CompiledCacheNotClearedException unless both are gone. ⚠ No result to ignore.
     */
    fun discard(archiveDigest: String)

    /** Deletes every compiled directory whose digest is not in [liveDigests], and the staging area. */
    fun sweep(liveDigests: Set<String>): CompiledSweep
}

/** Which install a [PrepareOutcome] is about: the model, and the digests of the towers it compiles. */
data class PrepareKey(val modelId: String, val towerDigests: List<String>)

/** How a call of [ModelPreparer.prepare] ended, **as the disk and readiness confirmed it**. */
sealed interface PrepareOutcome {
    /** No install record. The cache holds no compiled model any more. */
    data object NoModel : PrepareOutcome

    /**
     * Every tower has its marker and readiness reads the install as prepared. [state] is its
     * answer, and [preflight] what the probe that followed said, `null` when none was asked.
     * ⚠ Or [state] is [AiReadinessState.Unsuitable]: the install is answered, and nothing of it
     * is compiled, whatever markers it has (§14.5).
     */
    data class Prepared(val state: AiReadinessState, val preflight: PreflightOutcome?) : PrepareOutcome

    /**
     * Every tower has its marker and readiness still does not read the install as prepared,
     * for [why]: something no prepare can make (the vocabulary's file is gone, or is another
     * size). ⚠ What repairs it is a download of the model, which fetches what is missing.
     */
    data class Incomplete(val why: String) : PrepareOutcome

    /** Not now, and nothing was changed: Photos is not in the foreground. Tried at the next one. */
    data object NotInForeground : PrepareOutcome

    /** The runtime was torn down. Nothing more is written by this graph. */
    data object Abandoned : PrepareOutcome

    /**
     * The index, a marker or the cache's directory could not be **read**, or the index is not
     * one this build wrote. Nothing was compiled and nothing deleted on the strength of it.
     */
    data class Unreadable(val why: String) : PrepareOutcome

    /**
     * A step of [role]'s prepare failed, or (with [role] = `cache`) something could not be
     * deleted. No marker was written for it; tried again at the next trigger.
     */
    data class Failed(val role: String, val why: String, val space: SpaceShort? = null) : PrepareOutcome

    /** The room a tower's prepare was refused for: what it needs free, and what there is. */
    data class SpaceShort(val neededBytes: Long, val freeBytes: Long)
}

/**
 * [ModelStoreChanges] for a graph that serves **one** model ([modelId]): a commit or a removal
 * of that model runs inside readiness's writer 3; a change to any other model runs as it is.
 *
 * ⚠⚠ Writer 3 stops the model being served, closes its towers, cancels a running pass and
 * forgets its failed probes, for the length of the change. Wrapped around *every* store change,
 * the download or the removal of another model did all of that to this one.
 */
class PinnedModelChanges(private val readiness: AiReadiness, private val modelId: String) : ModelStoreChanges {
    /** ⚠ Which model is not said: treated as this one's, which is the safe side. */
    override suspend fun <T> changing(change: suspend () -> T): T = readiness.storeChanging(change)

    override suspend fun <T> changing(modelId: String, change: suspend () -> T): T =
        if (modelId == this.modelId) readiness.storeChanging(change) else change()
}

/** A [PrepareOutcome] and the install it is about (`null`: there was none, or it could not be read). */
data class PrepareReport(val key: PrepareKey?, val outcome: PrepareOutcome)

/**
 * How a call ended that **read no install** (Photos was not in the foreground; the index could
 * not be read; the graph was torn down), with what makes it stale.
 *
 * ⚠⚠ Such an outcome has no [PrepareKey]: it is about a moment, not an install. Unstamped, it
 * would stand until the next keyed attempt ended, and a model committed in between would be
 * described by it: a new generation would say *could not be read*, or *not in the foreground*,
 * from a call made before it existed.
 *
 * @param about the [PreflightKey] **readiness was about** when the call was refused, or `null`
 *   when readiness had read nothing or could not read. A screen applies the refusal only to an
 *   install whose key this is. ⚠ It cannot go stale silently across an install: every commit
 *   and removal of the model goes through readiness's writer 3, which reconciles, and a new
 *   install is a new key (other towers, another vocabulary). A store change that keeps the key
 *   is the same install for everything a prepare reads.
 *   ⚠ `null` is about **no** install, and is applied to none.
 */
data class PrepareRefusal(val about: PreflightKey?, val outcome: PrepareOutcome)

/**
 * The prepare step (`ios-on-device-inference.md` §14.17): what takes an installed model from
 * [AiReadinessState.Preparing] to a compiled one, and keeps the compiled cache to **the installed
 * generation and nothing else**.
 *
 * One call, in order, under the process's step lock:
 * 1. the install record, read from the store;
 * 2. **the sweep**: every compiled directory the record does not name, and the staging area. This
 *    is the delete-before-compile on an update and the reclaim of whatever a crash left;
 * 3. each tower that is not ready: compiled, opened once, marked;
 * 4. readiness reads the markers (writer 1), and the preflight is asked ([preflight]).
 *
 * A removal's eviction is the same sweep with no record, made by the removal itself ([evicted]).
 *
 * ⚠⚠ **Invariants** (each early return below is checked against them):
 * - *I1* an install whose state is `Preparing` always has one of: a prepare running; a prepare
 *   to come (every launch, every return to the foreground and every commit starts one); or an
 *   outcome here that says why not: [lastReport] for the install, [lastRefusal] for a call
 *   that got as far as no install;
 * - *I2* a compiled directory exists ⇒ the install record names its digest, or the next sweep
 *   removes it, or a sweep said it could not ([PrepareOutcome.Failed]);
 * - *I3* a marker exists ⇒ every step of that tower's prepare ran to its end in one piece,
 *   and the directory beside it was not touched since (a discard removes the marker first);
 * - *I4* nothing is compiled, deleted or concluded after a read that failed;
 * - *I5* a graph that was torn down starts no step, and nothing is **compiled** while Photos
 *   is not in the foreground. ⚠ A removal's eviction is not a compile: it is file deletes, and
 *   it runs wherever the removal does.
 *
 * ⚠⚠ **A call that changes nothing writes nothing.** What a prepare will do is decided before
 * readiness is touched, and a call that finds every tower ready and nothing to sweep ends
 * there: no state is published, no session closed, no pass cancelled. It ran at every return to
 * the foreground, and a `Ready` model was un-served and its towers unloaded each time.
 *
 * ⚠⚠ **Everything that changes the cache is reached through [acts]**, and each of its calls asks
 * its question first: [compiled] is not a property, so a change made past the gate does not
 * compile. A teardown or a move to the background can land at any wait, so the question is asked
 * at every act and at every step inside a tower's prepare, not remembered from the call's start.
 *
 * ⚠⚠ **No probe runs beside a prepare, and no prepare beside a probe** ([AiReadiness.cacheChanging]):
 * a probe that has begun is waited for, and none begins until the prepare has ended. So a
 * probe's answer is always about towers that were there for its whole length, and its pair of
 * towers is never resident beside the first open of another.
 *
 * ⚠ Under the step lock for its whole length, by decision: a commit, a removal or a transfer's
 * report waits for a prepare in flight (§3.4 measured about 3 s and 6 s a tower), and none of
 * them can change the store under a compile. ⚠ Readiness's own lock is **not** held across a
 * compile: only around the discard of a tower that may be served.
 *
 * @param preflight the probe that follows a **recompile**, asked outside every lock; its
 *   argument is always `true` (it is not a probe to back off from). ⚠ Every other probe is
 *   [check]'s, by the state. It is the caller's to say which foreground it is counted in.
 * @param currentKey the preflight's key for what is installed now, by the function readiness
 *   reads it with.
 * @param notPrepared readiness's own reason for not reading the install as prepared, by the
 *   very function it reads with; `null` when it does.
 * @param beforeLoad awaited before anything is compiled: no model is opened while an earlier
 *   graph of the process is still unloading its own, nor while a probe holds a pair.
 * @param steps the process's step lock, the downloader's own.
 */
class ModelPreparer(
    private val store: ModelStore,
    compiled: CompiledModels,
    private val readiness: AiReadiness,
    private val preflight: suspend (afterRecompile: Boolean) -> PreflightOutcome,
    private val notPrepared: () -> String?,
    /** The key a probe would run under now: readiness's own. What a probe's answer is compared with, whole. */
    private val currentKey: () -> PreflightKey,
    private val inForeground: () -> Boolean,
    private val beforeLoad: suspend () -> Unit,
    private val steps: Mutex,
    private val towers: List<String> = listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT),
    val modelId: String = VisionModel.PinnedSigLip.modelId,
    /** When a first open begins, for its [AttemptBegun] and the backoff it feeds. */
    private val nowEpochMs: () -> Long = ::systemNowEpochMs,
    /**
     * Which foreground of the scene it is now, or `null` in none: what a load's record carries
     * ([AttemptBegun.foreground]). ⚠ No default: a seam that answered `null` would make every
     * load "began outside the foreground", and no kill would ever be counted.
     */
    private val foregroundNow: () -> Long?,
) {
    /** Whether [changed] is the one model this graph prepares: what a store change is asked before it starts a check. */
    fun concerns(changed: String): Boolean = changed == modelId

    /**
     * ⚠⚠ Each tower's compile and its first open run with their [AttemptBegun] on disk, under the
     * key of what is installed and the foreground they began in (§14.5): one that kills Photos in
     * the foreground is counted at the next launch, and at
     * [PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE] the install is `Unsuitable` and
     * [AiReadiness.loadGate] compiles none of it again.
     */
    private val loads = LoadRecording { kind, role, _, load ->
        readiness.attempting(kind, role, currentKey(), nowEpochMs(), foregroundNow()) { load() }
    }

    private val acts = Acts(compiled, readiness, { beforeLoad() }, ::alive, ::aliveAndInTheForeground, loads)

    /**
     * The cache's calls. ⚠⚠ **Everything that deletes or writes under the cache is a member of
     * [Changes], and a [Changes] exists only inside [changing]**, which holds the one exclusion:
     * no probe begins ([AiReadiness.cacheChanging]), and a probe that had begun, and an earlier
     * graph's unload, have been waited for ([quiet]). So a path that deletes cannot be written
     * outside it — a sweep outside it would delete the towers of a probe still running on the
     * generation an update had just replaced.
     * ⚠ Inside, each asks its question first: [alive] before a delete, [compiling] (alive, and
     * in the foreground) before a compile and at each of its steps.
     */
    private class Acts(
        private val compiled: CompiledModels,
        private val readiness: AiReadiness,
        private val quiet: suspend () -> Unit,
        private val alive: () -> Unit,
        private val compiling: () -> Unit,
        private val loads: LoadRecording,
    ) {
        fun tower(role: String, digest: String): CompiledTower = compiled.tower(role, digest)

        suspend fun <T> changing(change: suspend Changes.() -> T): T {
            alive()
            return readiness.cacheChanging {
                quiet()
                Changes().change()
            }
        }

        inner class Changes {
            suspend fun prepare(role: String, archivePath: String, digest: String) {
                compiling()
                compiled.prepare(role, archivePath, digest, compiling, loads)
            }

            fun discard(digest: String) {
                compiling()
                compiled.discard(digest)
            }

            fun sweep(live: Set<String>): CompiledSweep {
                alive()
                return compiled.sweep(live)
            }

            /** A removal's discard: file deletes, wherever Photos is. ⚠ Not [discard], which is a compile's. */
            fun evict(digest: String) {
                alive()
                compiled.discard(digest)
            }
        }
    }

    /** Why a step is not taken. Never leaves this class. */
    private class Stopped(val outcome: PrepareOutcome) : Exception()

    private companion object {
        /** [PrepareOutcome.Failed.role] when what failed was not one tower's prepare. */
        const val THE_CACHE: String = "cache"
    }

    private fun alive() {
        if (readiness.isRetired()) throw Stopped(PrepareOutcome.Abandoned)
    }

    /** *I5*, asked immediately before every compile and every step of one. */
    private fun aliveAndInTheForeground() {
        alive()
        if (!inForeground()) throw Stopped(PrepareOutcome.NotInForeground)
    }

    private val last = MutableStateFlow<PrepareReport?>(null)
    private val refusal = MutableStateFlow<PrepareRefusal?>(null)

    /**
     * How the last call that **read an install** ended, and for which: what a screen says
     * about a model that is not `Ready`. ⚠ A call that never got to an install (not in
     * the foreground, an index that could not be read) does not replace it: see [lastRefusal].
     */
    val lastReport: StateFlow<PrepareReport?> = last.asStateFlow()

    /**
     * Why the **last** call ended before it had read an install, or `null` when it read one:
     * *not in the foreground*, *could not be read*, *torn down*. What 5d shows beside
     * [lastReport] while it is not `null`.
     */
    val lastRefusal: StateFlow<PrepareRefusal?> = refusal.asStateFlow()

    /**
     * What a prepare of [record] is about: the key [lastReport] carries for it. ⚠ The one place
     * it is built, for the preparer and for whoever compares a report with what is installed.
     */
    fun keyFor(record: InstalledModelData): PrepareKey =
        PrepareKey(modelId, towers.mapNotNull { role -> record.artifacts.firstOrNull { it.role == role }?.sha256 })

    /** The last outcome, if it is about [key]. It says nothing about any other install. */
    fun outcomeFor(key: PrepareKey): PrepareOutcome? = last.value?.takeIf { it.key == key }?.outcome

    /**
     * The installs whose towers this process has already compiled a second time because the
     * probe could not load them. ⚠ Memory, and rightly: what bounds a model that never loads
     * is the preflight's own count, which is on disk (three failures are a failed install);
     * this only keeps one process from compiling the same bytes over and over.
     */
    private val recompiled = mutableSetOf<PrepareKey>()

    /**
     * The store is **about to** remove the model, in the step that holds the step lock now: its
     * compiled towers are deleted first, and the removal is made only if they went.
     *
     * ⚠⚠ **What `DeleteModel` returning means on iOS: the record, and the compiled towers it
     * named, are gone.** Deleted *after* the record, a delete of the towers that failed would be
     * recorded and the removal reported as done, with ~740 MB of derived files left for a later
     * sweep. In this order a failed delete fails the removal,
     * typed, and the model is **still installed**: nothing claims a deletion that did not
     * happen, and deleting again tries again.
     * - The towers go inside the one exclusion (no probe beside it) and inside readiness's
     *   writer, which stops serving them first.
     * - ⚠ A process that dies between this and the removal leaves a model that is installed
     *   and not compiled: the next prepare compiles it again. That is what a crash in the
     *   middle of a delete costs.
     * - ⚠ The caller holds [steps]; this takes neither it nor this class's own call lock.
     *
     * @throws CompiledCacheNotClearedException and then the record must not be removed.
     */
    suspend fun evicting(removed: String) {
        // ⚠⚠ Only when it is THIS model that is being removed. The store holds other models too,
        // and the removal of one of them once discarded this one's towers and stopped serving it.
        if (removed != modelId) return
        val record: InstalledModelData = try {
            store.installed().firstOrNull { it.modelId == modelId }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            // The removal itself meets the same index, and says so.
            return
        } ?: return
        val key: PrepareKey = keyFor(record)
        try {
            acts.changing { readiness.compiledChanging { key.towerDigests.forEach { evict(it) } } }
        } catch (stopped: Stopped) {
            // A graph that was torn down removes nothing; the downloader has asked already.
            return
        } catch (notCleared: CompiledCacheNotClearedException) {
            record(PrepareReport(key, PrepareOutcome.Failed(THE_CACHE, notCleared.message.orEmpty())))
            throw notCleared
        }
    }

    /**
     * The store has removed the model: whatever else no record names is swept (orphans of an
     * earlier generation, a staging area). ⚠ The model's own towers went before the record
     * ([evicting]); a failure here is recorded and does not undo a removal that happened.
     */
    suspend fun evicted(removed: String) {
        // ⚠ Whichever model went: the sweep keeps what ANY install names, and takes the rest.
        val report: PrepareReport = try {
            underTheStepLock(discard = null, compile = false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PrepareReport(null, PrepareOutcome.Failed(THE_CACHE, failure.message ?: failure.toString()))
        }
        record(report)
    }

    /**
     * Brings the compiled cache in line with the install record, and says how that went.
     * At launch, on every return to the foreground, and after every commit.
     */
    suspend fun prepare(): PrepareOutcome = calls.withLock {
        // ⚠⚠ It asks for NO probe. Whether one is owed is not this call's to know from what it
        // did ("I compiled, so I probe" missed the update that keeps both towers and changes
        // the vocabulary: nothing to compile, and a new key that awaits its verdict). It is
        // owed by the STATE, and asked for by the one sequence every trigger runs ([check]).
        val report: PrepareReport = step(discard = null, probedUnder = null)
        record(report)
        report.outcome
    }

    /** A probe's answer and the towers it was of (`null`: of none). */
    class Probed(val about: PreflightKey?, val outcome: PreflightOutcome)

    /** What one [check] did: how the prepare ended, and the probe's answer that stands. */
    class Checked(val prepared: PrepareOutcome, val probed: PreflightOutcome)

    /**
     * The middle of the one sequence **every** trigger runs (a launch, the scene becoming
     * active, a commit): the prepare, then the probe **when the state says one is owed**, then
     * what its answer leads to.
     *
     * ⚠⚠ One owner for the probe. There were two (this class after a compile, the foreground's
     * check otherwise) and a flag to keep them from both asking; each new case fell between
     * them. The rule is the state: after the prepare, a model that readiness says awaits its
     * verdict is probed, whatever the prepare did, by this call and by nothing else.
     *
     * @param probe the foreground's probe. Asked only when a verdict is awaited. Its argument:
     *   whether this probe is **owed past the backoff** ([owed]).
     */
    suspend fun check(probe: suspend (pastTheBackoff: Boolean) -> Probed): Checked =
        // ⚠ Counted after it returned, here, so that no caller can make a check that is not.
        checkOnce(probe).also { ended.update { it + 1 } }

    private suspend fun checkOnce(probe: suspend (pastTheBackoff: Boolean) -> Probed): Checked {
        val prepared: PrepareOutcome = prepare()
        if (readiness.state.value != AiReadinessState.AwaitingVerdict) return Checked(prepared, PreflightOutcome.NotAwaiting)
        // ⚠ Noted BEFORE the probe begins: what its answer is checked against ([probed]).
        val generation: Long = generation()
        val debt: Owed? = owedNow()
        val answered: Probed = probe(debt != null)
        if (debt != null && answered.about == debt.about) paidBy(debt, answered.outcome)
        val stands: PreflightOutcome = probed(answered.about, generation, answered.outcome)
        return Checked(last.value?.outcome ?: prepared, stands)
    }

    /**
     * How many checks of this graph have **ended**, for a screen (§14.18): until the first has,
     * [lastReport], [lastRefusal] and the preflight's report say nothing about what is
     * installed, because nothing has asked yet.
     * ⚠ Counted by [check] itself after it returned, never before: a check that threw or was
     * cancelled is not one that ended.
     */
    val checksEnded: StateFlow<Long> get() = ended.asStateFlow()

    /**
     * The two figures of a prepare refused for want of room. ⚠ Also when the cleanup after the
     * refusal failed too: the cache then throws *not cleared* with the refusal suppressed
     * under it, and the figures are still what a screen needs.
     */
    private fun spaceShortOf(failure: Throwable): PrepareOutcome.SpaceShort? =
        (sequenceOf(failure) + failure.suppressedExceptions.asSequence())
            .filterIsInstance<CompiledSpaceInsufficientException>()
            .firstOrNull()
            ?.let { PrepareOutcome.SpaceShort(it.neededBytes, it.freeBytes) }

    /**
     * The install a tower is being **compiled** for now, or `null`: for a screen's *Preparing…
     * about ten seconds*, said only of the install it is about.
     * ⚠⚠ Not "the cache is changing" (`AiReadiness.cacheChanges`): that is also true for the
     * sweep every check makes, for an eviction, and while a prepare only waits for a probe or
     * an earlier graph's unload. Read as *preparing*, an install that cannot be prepared
     * (incomplete, no room) would say "about ten seconds" at every launch.
     * ⚠ One prepare at a time holds the step lock, so one flag is enough.
     */
    val compiling: StateFlow<PrepareKey?> get() = compilingNow.asStateFlow()

    /** The install whose tower is being compiled, or `null`. ⚠ With its key: see [compiling]. */
    private val compilingNow = MutableStateFlow<PrepareKey?>(null)

    private val ended = MutableStateFlow(0L)

    /**
     * A probe that is **owed past the backoff**: the towers of [about] were compiled a second
     * time, at [generation], and no probe of them has run to a recorded answer since.
     *
     * ⚠⚠ Not the same fact as "the one recompile was made" ([recompiled]). The allowance is
     * spent when the recompile completes. The probe that was to follow it can then be refused
     * (the scene left in between), and were nothing to owe it any more, back in the foreground
     * the ordinary probe would meet the failed probe's backoff, and the rebuilt towers would
     * wait it out, unverified. The debt stands until a
     * probe of these very towers has **run**, whoever asks for it.
     *
     * ⚠ Bound to the whole key and the generation: it is not paid to, or inherited by, another
     * install or towers compiled again since. ⚠ Memory, like the allowance: a new process owes
     * nothing, has a fresh allowance, and its first probe waits out the backoff the failed
     * probe left on disk.
     */
    private class Owed(val about: PreflightKey, val generation: Long)

    @kotlin.concurrent.Volatile
    private var owed: Owed? = null

    /** The debt, when it is for what is installed and compiled now; one for anything else is dropped. */
    private fun owedNow(): Owed? {
        val debt: Owed = owed ?: return null
        if (debt.generation == compiles && isWhatWouldRunNow(debt.about)) return debt
        owed = null
        return null
    }

    /** [outcome] is what a probe of [debt]'s towers said: the debt is paid when that probe **ran**. */
    private fun paidBy(debt: Owed, outcome: PreflightOutcome?) {
        val ran: Boolean = when (outcome) {
            // An answer readiness recorded: a verdict, or a miss that was counted.
            is PreflightOutcome.Passed, is PreflightOutcome.Retry, is PreflightOutcome.Unsuitable, PreflightOutcome.InstallFailed -> true
            // Not run (not in the foreground, busy, nothing to probe, no probe in this build),
            // or run and not recorded: still owed.
            else -> false
        }
        if (ran && owed === debt) owed = null
    }

    /**
     * How many times this graph has changed a compiled tower (a compile, or a discard). What a
     * probe made elsewhere notes **before it begins**, and hands back with its answer ([probed]).
     */
    fun generation(): Long = compiles

    @kotlin.concurrent.Volatile
    private var compiles: Long = 0

    /**
     * A probe made elsewhere (the foreground's own) ended as [outcome]. When it says a **tower**
     * would not load, or answered wrongly, the towers are compiled once more and probed again.
     *
     * ⚠⚠ **An answer is acted on only for what it is about.** This call waits for [calls], and a
     * prepare of another generation can run in that wait (an update lands under a probe). The
     * answer would then name the *last install this graph read*, which is the new one: its towers
     * would be discarded and its one recompile used up because the old generation had failed. So
     * the answer carries its identity and is checked here, under the lock:
     * - [about]: **the whole key the probe ran under**, as the preflight reports it, compared
     *   whole with the key that would run now ([currentKey]). ⚠⚠ Not a projection of it.
     *   Reduced to the two towers' digests, an update that changes only the vocabulary would
     *   look like the same install: the old towers' failure would discard the new install's
     *   towers and spend its recompile;
     * - [atGeneration]: [generation] as it was before the probe began. The same digests
     *   compiled again since are other towers: an answer delivered after a recompile, or two
     *   foreground checks' answers arriving the wrong way round, is about what is gone.
     *
     * ⚠ An answer that is not about what is here is dropped **for this purpose**: nothing is
     * compiled and nothing recorded. It is still returned, since it is what that probe said.
     *
     * @return the answer that stands: the second probe's when there was one, else [outcome].
     */
    suspend fun probed(about: PreflightKey?, atGeneration: Long, outcome: PreflightOutcome): PreflightOutcome = calls.withLock {
        val held: PrepareReport = last.value ?: return@withLock outcome
        val key: PrepareKey = held.key ?: return@withLock outcome
        if (about == null || !isWhatWouldRunNow(about) || atGeneration != compiles) return@withLock outcome
        val was: PrepareOutcome.Prepared = held.outcome as? PrepareOutcome.Prepared ?: return@withLock outcome
        val report: PrepareReport = afterAProbe(key, about, was, outcome)
        record(report)
        (report.outcome as? PrepareOutcome.Prepared)?.preflight ?: outcome
    }

    /** [probed] is what a probe of [key]'s towers said: recorded, and answered with one recompile when it is theirs. */
    private suspend fun afterAProbe(key: PrepareKey, about: PreflightKey, prepared: PrepareOutcome.Prepared, probed: PreflightOutcome?): PrepareReport {
        val report = PrepareReport(key, prepared.copy(preflight = probed))
        // ⚠ A compiled tower that would not load, or answered wrongly, is compiled once more
        // from the verified archive: a `.mlmodelc` is derived, and can be derived again.
        // ⚠⚠ Only for a TOWER. A vocabulary that does not parse is not cured by 1.8 GB of
        // compiling, and is not answered with it.
        if (probed == null || !probed.isATowersFailure() || key in recompiled) return report
        val again: PrepareReport = step(discard = key, probedUnder = about)
        val second: PrepareOutcome = again.outcome
        if (again.key != key || second !is PrepareOutcome.Prepared) return again
        // ⚠⚠ And only when the install is STILL the one the probe was of: a commit can take the
        // step lock between this call's first look and its pass, which then discarded nothing.
        // A recompile that was not made is not remembered as made, and is followed by no probe.
        if (!isWhatWouldRunNow(about)) return again
        // ⚠ Remembered only once it was DONE: a recompile that was stopped (the scene left,
        // the index could not be read) is still owed.
        recompiled += key
        // ⚠⚠ Not a probe to back off from: what failed a moment ago is not what is on the
        // disk now. ⚠⚠ And OWED from here until one has run: the probe just below can be
        // refused (the scene left while the towers compiled), and the allowance is spent.
        val debt = Owed(about, compiles)
        owed = debt
        val probed: PreflightOutcome? = probe(afterRecompile = true)
        paidBy(debt, probed)
        return PrepareReport(key, second.copy(preflight = probed))
    }

    private fun record(report: PrepareReport) {
        // ⚠⚠ An outcome about no install does not erase what was said about one: "needs N bytes
        // free" stays readable while Photos is in the background.
        if (report.key != null) {
            last.value = report
            refusal.value = null
        } else {
            refusal.value = report.outcome.takeIf { it != PrepareOutcome.NoModel }?.let { outcome ->
                // ⚠⚠ Stamped with the install readiness was about when the call was refused: see [PrepareRefusal].
                PrepareRefusal(about = (readiness.standing.value as? ReadinessStanding.For)?.key, outcome = outcome)
            }
            if (report.outcome == PrepareOutcome.NoModel) last.value = report
        }
    }

    /** One call at a time for a graph: what a call remembers ([recompiled]) is its own. */
    private val calls = Mutex()

    /** The probe's answer, or `null` when asking threw: the markers are written either way. */
    private suspend fun probe(afterRecompile: Boolean): PreflightOutcome? = try {
        preflight(afterRecompile)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        println("ModelPreparer -> the model is prepared and the preflight could not be asked: $failure")
        null
    }

    private fun PreflightOutcome.isATowersFailure(): Boolean =
        this is PreflightOutcome.Retry && (finding as? PreflightFinding.Failed)?.beforeAnyTower == false

    /**
     * Whether [about] is, **whole**, the key a probe would run under now. ⚠ A key that cannot
     * be read now is not it: nothing is discarded on the strength of a comparison that failed.
     */
    private fun isWhatWouldRunNow(about: PreflightKey): Boolean = try {
        currentKey() == about
    } catch (failure: Exception) {
        false
    }

    private suspend fun step(discard: PrepareKey?, probedUnder: PreflightKey?): PrepareReport {
        try {
            alive()
        } catch (stopped: Stopped) {
            return PrepareReport(null, stopped.outcome)
        }
        return steps.withLock { underTheStepLock(discard, compile = true, probedUnder = probedUnder) }
    }

    /** One pass. @param discard the install whose compiled towers go first. @param compile `false`: a removal's eviction. */
    private suspend fun underTheStepLock(discard: PrepareKey?, compile: Boolean, probedUnder: PreflightKey? = null): PrepareReport {
        // *I4*: an index that cannot be read ends the call here, with nothing touched.
        val record: InstalledModelData? = try {
            store.installed().firstOrNull { it.modelId == modelId }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            return PrepareReport(null, PrepareOutcome.Unreadable(unreadable.message.orEmpty()))
        }
        // ⚠⚠ *I4* again: "no record" from bytes that were not an index is not "no model". The
        // store sweeps nothing of its own against such bytes, and neither is a tower swept here.
        if (record == null && !store.holdsAnIndex()) {
            return PrepareReport(null, PrepareOutcome.Unreadable("the model index is not one this build wrote; nothing is deleted against it"))
        }
        val key: PrepareKey? = record?.let(::keyFor)
        val outcome: PrepareOutcome = try {
            // ⚠⚠ The discard is for the install the probe was of, checked again HERE, under the
            // lock a commit takes: the whole key, not the towers alone.
            change(record, key, discard.takeIf { probedUnder == null || isWhatWouldRunNow(probedUnder) }, compile)
        } catch (stopped: Stopped) {
            stopped.outcome
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // ⚠⚠ Whatever the cache or readiness threw: said, for this install (*I1*), and
            // never out of this call, where the callers would only log it.
            PrepareOutcome.Failed(THE_CACHE, failure.message ?: failure.toString())
        }
        return PrepareReport(key, outcome)
    }

    private suspend fun change(record: InstalledModelData?, key: PrepareKey?, discard: PrepareKey?, compile: Boolean): PrepareOutcome {
        // ⚠⚠ What the sweep may not take: every digest ANY install record names, of any model.
        // This class is about one model (`modelId`); the cache's directory is not. Sweeping
        // "what this model's record does not name" would take another model's compiled towers
        // as orphans the day a second one is compiled there.
        val live: Set<String> = try {
            store.installed().flatMapTo(mutableSetOf()) { installed -> installed.artifacts.map { it.sha256 } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            return PrepareOutcome.Unreadable(unreadable.message.orEmpty())
        }
        // ⚠⚠ From here to the end no probe begins, one that had begun has ended, and no model of
        // an earlier graph is still loaded: before the first file is deleted or written.
        val ended: PrepareOutcome? = acts.changing {
            // ⚠⚠ FIRST, before anything is compiled: at most one generation's compiled models,
            // and the disk's peak is the new generation's prepare alone (§14.13). ⚠ Never a
            // live digest, so nothing that is served: readiness's state is not touched.
            when (val swept: CompiledSweep = sweep(live)) {
                // *I4*. ⚠ And nothing is compiled beside a directory that could not be listed:
                // what it holds is not known, and the disk budget assumes one generation.
                is CompiledSweep.Unlisted -> return@changing PrepareOutcome.Unreadable(swept.why)
                is CompiledSweep.Swept ->
                    // ⚠ *I2*: a delete that did not delete is said, and nothing is compiled beside it.
                    if (swept.left.isNotEmpty()) return@changing PrepareOutcome.Failed(THE_CACHE, "could not delete ${swept.left}")
            }
            if (record == null || key == null) return@changing PrepareOutcome.NoModel
            if (!compile) return@changing PrepareOutcome.Prepared(readiness.state.value, preflight = null)
            for (role in towers) {
                if (record.artifacts.none { it.role == role }) {
                    return@changing PrepareOutcome.Failed(role, "the install record of '$modelId' has no $role artifact")
                }
            }
            // ⚠ Only when the install is still the one the probe was about: the lock was not
            // held between the probe and here.
            val toDiscard: Boolean = discard != null && discard == key
            // ⚠⚠ What this call WILL do, decided before readiness's state is touched.
            val missing: List<String> = towers.filter { role ->
                when (val held: CompiledTower = acts.tower(role, record.digestOf(role))) {
                    is CompiledTower.Ready -> toDiscard
                    is CompiledTower.Unreadable -> return@changing PrepareOutcome.Unreadable(held.why)
                    CompiledTower.Absent -> true
                }
            }
            if (missing.isEmpty()) return@changing said(readiness.state.value)
            // ⚠⚠ Before anything is discarded or compiled (§14.5): an install answered by loads
            // the process did not survive is not loaded again, and a load whose record could not
            // be written is not begun, so its 1.8 GB compile is not made either.
            when (readiness.loadGate()) {
                AiReadiness.LoadGate.GO -> Unit
                AiReadiness.LoadGate.UNREADABLE ->
                    return@changing PrepareOutcome.Unreadable("the preflight's record could not be read: nothing is compiled whose first open could not be counted")
                AiReadiness.LoadGate.ANSWERED -> return@changing PrepareOutcome.Prepared(AiReadinessState.Unsuitable, preflight = null)
            }

            aliveAndInTheForeground()
            compiles += 1
            if (toDiscard) {
                // ⚠⚠ The one change that touches towers a `Ready` model is served from: inside
                // readiness's writer, which leaves `Ready` first.
                val notGone: String? = readiness.compiledChanging {
                    try {
                        key.towerDigests.forEach { discard(it) }
                        null
                    } catch (notCleared: CompiledCacheNotClearedException) {
                        notCleared.message.orEmpty()
                    }
                }
                if (notGone != null) return@changing PrepareOutcome.Failed(THE_CACHE, notGone)
            } else {
                // ⚠⚠ A tower is about to be written where readiness may still believe one is:
                // it reads the cache as it is NOW, and leaves `Ready` if it was there on the
                // strength of a marker that has gone since. (It has usually read already: a
                // commit and a launch both make it read before this is reached.)
                readiness.markerWritten()
            }
            for (role in missing) {
                val artifact = record.artifacts.first { it.role == role }
                try {
                    // ⚠ Around the compile and nothing else: see [compiling].
                    compilingNow.value = keyFor(record)
                    try {
                        prepare(role, artifact.localPath, artifact.sha256)
                    } finally {
                        compilingNow.value = null
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (stopped: Stopped) {
                    throw stopped
                } catch (failure: Exception) {
                    return@changing PrepareOutcome.Failed(
                        role,
                        failure.message ?: failure.toString(),
                        // ⚠ As values, when the failure is one: the sentence is a log's.
                        space = spaceShortOf(failure),
                    )
                }
                // ⚠ What is reported is what the disk holds now, not that a call returned.
                when (val now: CompiledTower = acts.tower(role, artifact.sha256)) {
                    is CompiledTower.Ready -> Unit
                    is CompiledTower.Unreadable -> return@changing PrepareOutcome.Unreadable(now.why)
                    CompiledTower.Absent -> return@changing PrepareOutcome.Failed(role, "the prepare returned and left no ready marker")
                }
            }
            null
        }
        // Writer 1: the markers are there, and readiness reads them.
        return ended ?: said(readiness.markerWritten())
    }

    /**
     * Every tower is marked: what readiness makes of the install. ⚠⚠ By **its** predicate, not
     * by the markers alone: an install whose vocabulary file is gone has both towers ready and
     * is not prepared, and saying *prepared* of it would say nothing at every trigger for ever.
     */
    private fun said(state: AiReadinessState): PrepareOutcome {
        if (state != AiReadinessState.Preparing) return PrepareOutcome.Prepared(state, preflight = null)
        val why: String = try {
            notPrepared()
        } catch (failure: Exception) {
            return PrepareOutcome.Unreadable(failure.message ?: failure.toString())
        } ?: return PrepareOutcome.Prepared(state, preflight = null)
        return PrepareOutcome.Incomplete(why)
    }

    private fun InstalledModelData.digestOf(role: String): String = artifacts.first { it.role == role }.sha256
}

@OptIn(ExperimentalTime::class)
private fun systemNowEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
