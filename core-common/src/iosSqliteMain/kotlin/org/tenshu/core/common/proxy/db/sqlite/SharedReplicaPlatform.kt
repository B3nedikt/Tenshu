@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.core.common.proxy.db.sqlite

/**
 * ⚠⚠ **The shared replica's platform seam** (`docs/proposals/ios-shared-store-sqlite.md`,
 * Appendix B.12 (156)): the one surface of the store a second module may use, and only the UIKit
 * adapter in `:tenshu-sdk-ios-core` does. Everything behind it (the protection, its tokens and
 * quiesce, the worker, the gate) stays `internal` to `:core-common`.
 *
 * An `ERROR`-level opt-in, so app code cannot reach it by accident: a caller must say
 * `@OptIn(SharedReplicaPlatformApi::class)`, which a review will see. Every declaration under it is
 * also `@HiddenFromObjC`: `:core-common` is exported to every app framework, and Swift has no use
 * for it (the adapter is driven from Kotlin).
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "The shared replica's platform seam: only the UIKit adapter (:tenshu-sdk-ios-core) implements or drives it. ios-shared-store-sqlite.md B.12.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class SharedReplicaPlatformApi

/**
 * What the platform gives the store: background-time assertions and how much is left. Implemented
 * by the UIKit adapter over `UIApplication.beginBackgroundTask(withName:expirationHandler:)`,
 * `endBackgroundTask` and `backgroundTimeRemaining`; fakes in the tests.
 */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
interface SharedReplicaBackgroundTime {
    /**
     * Begins one assertion named [reason] and returns its id, or null when the platform grants no
     * time. [onExpired] is that assertion's own expiration handler: the adapter calls it from the
     * platform's, and the store ends the assertion (through [end]) before it returns. A throw is
     * read as null. Called on any thread.
     */
    fun begin(reason: String, onExpired: () -> Unit): Long?

    /** Ends the assertion [id]. Called exactly once per id begun, on any thread. */
    fun end(id: Long)

    /**
     * ⚠⚠ `backgroundTimeRemaining` in ms, asked only while the store believes the app is in the
     * background, **from any thread, often one holding the registry's lock, and every 100 ms from
     * the hold watchdog's queue** (B.7, B.11.4 (2), (6)). It must never wait for the main thread
     * (no `dispatch_sync` to main) and must not block. "No deadline" (`DBL_MAX`) is
     * `Long.MAX_VALUE`. A throw is read as 0.
     */
    fun backgroundTimeRemainingMs(): Long
}

/**
 * The loops rule P stops when the app leaves the foreground and starts when it returns (lease
 * draft §5.3 steps 2 and 3, Appendix B.13): the runtime's, handed to [SharedReplicaLifecycle.install].
 *
 * ⚠⚠ [start] and [stop] are called under the protection's state lock, on the main thread at a
 * transition: they must not block and must not touch the store (`stop()`, never a stop that
 * awaits). [awaitStopped] is called with no lock held, off the main thread, inside the shutdown;
 * it waits at most [timeoutMs] for what [stop] cancelled. A throw from any of them is logged.
 */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
interface SharedReplicaLoops {
    fun start()
    fun stop()
    fun awaitStopped(timeoutMs: Long)
}

/**
 * How a job the worker ran for a store ended, for the host (Appendix B.13): a summary, so that
 * none of the worker's internals become API. [databasePath] is the store's `objects.db`.
 */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
class SharedReplicaJobOutcome(
    val databasePath: String,
    val kind: Kind,
    /** For the log: the internal outcome as text. */
    val detail: String,
    /** The `REPLACEMENT` job's [ReplicaReplacement.jobId]; null for a walk's outcome (B.13 (183)). */
    val jobId: Long? = null,
) {
    enum class Kind {
        /** A `REPLACEMENT` run reached the job's finish: the store may be opened again for a runtime. */
        REPLACEMENT_FINISHED,

        /** A `REPLACEMENT` run stopped with the marker still there; the next kick or open goes on. */
        REPLACEMENT_STOPPED,

        /** A migration's run met the background (S13); the next foreground's kick goes on. */
        REPLACEMENT_NEEDS_FOREGROUND,

        /** This build may not resume the job: the update surface (§6, §7). */
        REPLACEMENT_SKEW,

        /** `replica/CORRUPT` exists. */
        REPLACEMENT_CORRUPT,

        /** The confirmation walk read the store whole without a finding: the fence may be lifted. */
        WALK_CLEAN,

        /** The walk found damage and the marker is durable. */
        WALK_CONFIRMED,

        /** The walk is still owed (deferred, progressed, stalled, no verdict, or nothing pending). */
        WALK_PENDING,
    }

    /** The store may be opened again for a runtime: a finished job or a clean walk. */
    val reopen: Boolean get() = kind == Kind.REPLACEMENT_FINISHED || kind == Kind.WALK_CLEAN

    override fun toString(): String = "SharedReplicaJobOutcome($kind, $databasePath: $detail)"
}

/**
 * The process's one handle on the shared replica's lifecycle: rule P's protection
 * ([ReplicaProtection]) and the worker ([ReplicaWorker]) behind the calls the platform makes.
 * Made by [create]; one per process. ⚠⚠ **The order is attach, install, open** (Appendix B.13):
 * [attach] every store's directory, then [install] once on the main thread, then the production
 * open (`SharedReplicaStore.open`), which refuses a registry not under an installed protection.
 */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
class SharedReplicaLifecycle private constructor(
    internal val protection: ReplicaProtection,
    internal val worker: ReplicaWorker,
) {
    /** The protection's own statement of the foreground: what the store judges by (B.11 (153)). */
    val isForeground: Boolean get() = protection.isForeground

    /** [install] has run. */
    val isInstalled: Boolean get() = protection.isInstalled

    /**
     * Puts the store in [directory] (its `objects.db`) under this process's protection and
     * worker: B.12 (156)'s attach. ⚠ Before [install] (the protection refuses after it), once
     * per directory (a second attach throws `IllegalStateException`).
     */
    fun attach(directory: String) {
        val registry = ReplicaHandles.forPath("$directory/${ReplicaOpener.DATABASE_NAME}", ReplicaLimits())
        protection.attach(registry)
        worker.attach(registry)
    }

    /**
     * At launch, once, after every registry is attached: [isForeground] is the application's state
     * then. A process launched in the background starts with its gates closed (lease draft §5.3
     * step 6). [loops] are what rule P stops and starts; null for none.
     */
    fun install(isForeground: Boolean, loops: SharedReplicaLoops? = null) =
        protection.install(isForeground, loops?.let(::LoopsWork) ?: ProtectedWork.None)

    /** The app entered the foreground. Idempotent. Then [kick]. */
    fun enteredForeground() = protection.enteredForeground()

    /**
     * ⚠⚠ The app entered the background (Q3): called on the main thread, inside the notification;
     * it takes the `background` token synchronously on the caller and dispatches its release (the
     * quiesce) off it.
     */
    fun enteredBackground() = protection.enteredBackground()

    /** The worker's trigger at a foreground and at a wake: owed walks and unfinished jobs scheduled on its queue. */
    fun kick() = worker.kick()

    /** §4.2.1's 30 s foreground checkpoint (B.12 (159)): on the worker's queue, never on main. The count run. */
    fun foregroundCheckpointTick(): Int = protection.foregroundCheckpointTick()

    /**
     * ⚠⚠ **An entry's explicit token** (lease draft §5.4; Appendix B.15 (199)): one assertion that
     * protects the store work an entry into the process does in the background (a wake, a
     * location event, a notification action), from before its first access until its release.
     * Null when the platform grants no time: the caller then does no store work (§5.4: "it must
     * not fall back to running unprotected"). [onExpired] is told once if the assertion expires
     * first, on the platform's thread; it must cancel the entry's work and must not block. ⚠ The
     * release of the last protection quiesces on the releasing thread (up to 1 s plus the 2 s
     * drain), so release off the main thread. Any thread may acquire; on the main thread the wait
     * for an owed reopen is bounded (B.5.2 (71)).
     */
    fun protectEntry(reason: String, onExpired: () -> Unit): SharedReplicaEntryToken? =
        protection.acquire("entry:$reason", ReplicaProtection.WAKE_DRAIN_MS, onExpired)?.let(::EntryToken)

    /**
     * ⚠⚠ **A task's token** (Appendix B.23; `ios-on-device-inference.md` §15.4, Q1): one protection
     * whose assertion **is** a running `BGTask` (`BGContinuedProcessingTask`, `BGProcessingTask`),
     * where [protectEntry]'s is a `beginBackgroundTask` that iOS ends within seconds whatever task
     * keeps the app running. Taken when the task's launch handler runs; nothing is begun on the
     * platform, so it is not refused for want of time. While it is live, §4.2.1's admission checks
     * read the task's time (no deadline until its expiry), not `backgroundTimeRemaining`.
     *
     * ⚠⚠ The owner ends it **before** completing the task, never after: the task is what keeps the
     * process from being suspended with a lock held (`0xdead10cc`).
     * - [SharedReplicaTaskToken.release] when the work is done: the release of the last protection
     *   quiesces on the calling thread (up to 1 s plus the 2 s drain), so release off the main thread.
     * - [SharedReplicaTaskToken.expire] from the task's `expirationHandler`: B.15.3's expiry, whose
     *   first row (it ends protection) interrupts every transaction before the 2 s drain. [onExpired]
     *   is called inside it, first; it must not block.
     * Null only when the token was gone before it could be handed out.
     */
    fun protectTask(reason: String, onExpired: () -> Unit = {}): SharedReplicaTaskToken? =
        protection.acquireTask("task:$reason", onExpired)?.let(::TaskToken)

    companion object {
        /**
         * [backgroundTime] is the platform's; [workerDispatch] runs a block once on the worker's
         * own **serial** queue, never inline and never on the main thread (B.11.4 (4)).
         * [onJobOutcome] is told how each job the worker ran ended, on the worker's queue: the
         * host's next open waits on it (B.13). ⚠ It must not throw and must not block on main.
         */
        fun create(
            backgroundTime: SharedReplicaBackgroundTime,
            workerDispatch: (() -> Unit) -> Unit,
            onJobOutcome: (SharedReplicaJobOutcome) -> Unit = {},
        ): SharedReplicaLifecycle {
            val protection = ReplicaProtection(BackgroundTimeAssertions(backgroundTime))
            val worker = ReplicaWorker(
                protection = protection,
                dispatch = workerDispatch,
                onReplacementOutcome = { replacement, outcome -> tell(onJobOutcome, replacementOutcome(replacement.registry.databasePath, outcome, replacement.jobId)) },
                onOutcome = { walk, outcome -> tell(onJobOutcome, walkOutcome(walk.databasePath, outcome)) },
            )
            return SharedReplicaLifecycle(protection, worker)
        }

        /** A throw out of the host's callback would end the worker's dispatch (a queue): logged instead. */
        private fun tell(onJobOutcome: (SharedReplicaJobOutcome) -> Unit, outcome: SharedReplicaJobOutcome) {
            try {
                onJobOutcome(outcome)
            } catch (t: Throwable) {
                println("[replica] the host's outcome callback threw: ${t.message}")
            }
        }

        internal fun replacementOutcome(path: String, outcome: ReplicaRunOutcome, jobId: Long? = null): SharedReplicaJobOutcome {
            val kind = when (outcome) {
                ReplicaRunOutcome.Finished -> SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED
                is ReplicaRunOutcome.Stopped -> SharedReplicaJobOutcome.Kind.REPLACEMENT_STOPPED
                ReplicaRunOutcome.NeedsForeground -> SharedReplicaJobOutcome.Kind.REPLACEMENT_NEEDS_FOREGROUND
                is ReplicaRunOutcome.Skew -> SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW
                is ReplicaRunOutcome.Corrupt -> SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT
            }
            return SharedReplicaJobOutcome(path, kind, outcome.toString(), jobId)
        }

        internal fun walkOutcome(path: String, outcome: ReplicaWalkOutcome): SharedReplicaJobOutcome {
            val kind = when (outcome) {
                is ReplicaWalkOutcome.Clean -> SharedReplicaJobOutcome.Kind.WALK_CLEAN
                is ReplicaWalkOutcome.Confirmed -> SharedReplicaJobOutcome.Kind.WALK_CONFIRMED
                else -> SharedReplicaJobOutcome.Kind.WALK_PENDING
            }
            return SharedReplicaJobOutcome(path, kind, outcome.toString())
        }
    }
}

/** An entry's token (Appendix B.15 (199)): released once, idempotent, from any thread. */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
interface SharedReplicaEntryToken {
    fun release()
}

/**
 * A task's token (Appendix B.23): [release] when the task's work is done, [expire] from its
 * `expirationHandler`; each idempotent, either after the other a no-op, and both return once the
 * token has ended. Then, and only then, the owner completes the task.
 */
@SharedReplicaPlatformApi
@kotlin.native.HiddenFromObjC
interface SharedReplicaTaskToken : SharedReplicaEntryToken {
    fun expire()
}

/** Internal, not private: the tests drive [SharedReplicaTaskProtection] over a real protection. */
@OptIn(SharedReplicaPlatformApi::class)
internal class TaskToken(private val token: ReplicaTaskToken) : SharedReplicaTaskToken {
    override fun release() = token.release()
    override fun expire() = token.expire()
}

@OptIn(SharedReplicaPlatformApi::class)
private class EntryToken(private val token: ReplicaProtectionToken) : SharedReplicaEntryToken {
    override fun release() = token.release()
}

/** The host's loops as rule P's work. */
@OptIn(SharedReplicaPlatformApi::class)
private class LoopsWork(private val loops: SharedReplicaLoops) : ProtectedWork {
    override fun start() = loops.start()
    override fun stop() = loops.stop()
    override fun awaitStopped(timeoutMs: Long) = loops.awaitStopped(timeoutMs)
}

/** The internal seam over the public one: nothing else of the protection is exposed. */
@OptIn(SharedReplicaPlatformApi::class)
private class BackgroundTimeAssertions(private val platform: SharedReplicaBackgroundTime) : ProtectionAssertions {
    override fun begin(reason: String, onExpired: () -> Unit): Long? = platform.begin(reason, onExpired)
    override fun end(id: Long) = platform.end(id)
    override fun backgroundTimeRemainingMs(): Long = platform.backgroundTimeRemainingMs()
}
