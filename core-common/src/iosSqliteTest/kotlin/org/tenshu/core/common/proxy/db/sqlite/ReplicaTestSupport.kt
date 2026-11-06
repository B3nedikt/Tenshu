@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.F_GETFD
import platform.posix.fcntl
import platform.posix.getdtablesize
import platform.posix.memcpy
import kotlin.random.Random
import kotlin.test.fail

/** A build for the tests: core v22 readable from v19, two services. */
internal fun testBuild(
    core: Int = 22,
    coreFloor: Int = 19,
    services: Map<String, Int> = mapOf("tenshu/file" to 3, "tenshu/sync" to 9),
    serviceFloors: Map<String, Int> = services.mapValues { (service, _) -> if (service == "tenshu/file") 2 else 1 },
    layout: Int = ReplicaSchema.LAYOUT,
) = ReplicaBuild(core, coreFloor, services, serviceFloors, layout)

/**
 * One "process" on a replica directory: its own registry, as another app would have. Two of
 * these on one directory exclude each other through SQLite alone.
 */
internal class ReplicaProcess(
    val directory: String,
    val limits: ReplicaLimits = ReplicaLimits(),
    val files: ReplicaFiles = PosixReplicaFiles,
    val name: String = "test",
) {
    val databasePath = "$directory/${ReplicaOpener.DATABASE_NAME}"
    val registry = ReplicaHandles(databasePath, limits).also {
        it.gate.failOnUnmatchedLeave = true
        // ⚠ The hold watchdog is OFF by default here (B.9 (131)): its timers are held and never
        // fired, and its clock never moves, so no test is under a real-time 1 s ceiling on a host
        // that can stall for longer. `ReplicaHoldWatchdogTest` opts in by firing them itself.
        it.holdWatchdog.after = { _, _ -> }
        it.holdWatchdog.clock = { 0L }
    }
    var protectedData = true

    /** The migration chains this "build" registers. None by default. */
    var steps: ReplicaMigrationSteps = ReplicaMigrationSteps { _, _ -> null }

    fun opener(build: ReplicaBuild = testBuild(), foreground: Boolean = true) =
        ReplicaOpener(directory, build, steps, name, foreground, { protectedData }, files, registry)

    fun open(build: ReplicaBuild = testBuild(), foreground: Boolean = true): ReplicaOpenResult =
        opener(build, foreground).open()

    /** A job on this process's registry, as an open or a wipe call of this build would make it. */
    fun replacement(build: ReplicaBuild = testBuild()) =
        ReplicaReplacement(registry, files, directory, build, steps, name, { protectedData })

    /** This process's confirmation walk (Appendix B.8): the one its hand-offs went to. */
    fun walk(): ReplicaConfirmationWalk = registry.confirmationWalk(name, files, testBuild()) { protectedData }

    /** Runs this process's walk until it is no longer progressing; the last outcome. At most [runs] runs. */
    fun walkToTheEnd(runs: Int = 1_000): ReplicaWalkOutcome {
        var outcome: ReplicaWalkOutcome = ReplicaWalkOutcome.NothingPending
        repeat(runs) {
            outcome = walk().run()
            if (outcome !is ReplicaWalkOutcome.Progressed) return outcome
        }
        return outcome
    }

    /** A raw connection on the store's file, closed after [block]. */
    fun <T> sql(block: (SqliteConnection) -> T): T {
        val connection = rawConnection(databasePath)
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }

    fun store(build: ReplicaBuild = testBuild()): SharedReplicaStore =
        when (val result = open(build)) {
            is ReplicaOpenResult.Ready -> result.store
            else -> fail("expected a store, got $result")
        }
}

/**
 * Turns this process's hold watchdog on **in real time** (Appendix B.10): the production timer
 * queue (`ReplicaWatchdogQueue`, `dispatch_after`) and a monotonic clock. ⚠ Opt-in only: every
 * suite runs with it off (B.9 (131)), and a test that calls this is under a real ceiling.
 */
internal fun ReplicaProcess.watchdogInRealTime() {
    registry.holdWatchdog.after = ReplicaWatchdogQueue::after
    registry.holdWatchdog.clock = kotlin.time.TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }
}

/**
 * Puts this process in protected background time with [remainingMs] left (a fixed fake, for the
 * admission checks and `CKPT_MAX`, Appendix B.10 (138)); [inForeground] takes it back out.
 */
internal fun ReplicaProcess.inBackground(remainingMs: Long = 60_000) {
    registry.gate.protection = object : ReplicaGateProtection {
        override fun awaitAdmission(gate: ReplicaGate, label: String) = Unit
        override fun remainingMs(): Long = remainingMs
        override val isForeground: Boolean = false
        override val isInstalled: Boolean = true
    }
}

internal fun ReplicaProcess.inForeground() {
    registry.gate.protection = null
}

/** A blob literal for raw SQL. */
internal fun blobLiteral(bytes: ByteArray): String =
    "x'" + bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + "'"

/** A `meta.wipe` frame as a wipe writes it, for a test that marks a store by hand. */
internal fun wipeFrame(endStamps: Map<String, Int> = emptyMap()): String =
    blobLiteral(WipeMarker("test", "test", 0L, endStamps).encode())

/** A `meta.migration` frame, at the first step of a chain taking `tenshu/file` from v2 to v3. */
internal fun migrationFrame(
    targets: Map<String, Int> = mapOf("tenshu/file" to 3),
    service: String = "tenshu/file",
    from: Int = 2,
    failure: MigrationFailure? = null,
    format: Int? = null,
): String = blobLiteral(
    MigrationMarker("test", 0L, targets, service, from, MigrationPhase.ROWS, null, failure).encode().also { frame ->
        format?.let { frame[0] = it.toByte() }
    },
)

internal fun tempReplica(): String {
    val directory = NSTemporaryDirectory() + "replica-test-" + Random.nextLong().toString(16) + "/replica"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    return directory
}

internal fun bytesOf(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val n = data.length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, n.convert()) } }
}

internal fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

internal fun row(hash: Long, type: String = "tenshu/file", payloadBytes: Int = 40): DbEntry =
    // A string, not a byte array: DbValue.ByteArrayValue compares by identity, a string by content.
    DbEntry(type, hash, listOf(DbValue.LongValue(hash), DbValue.StringValue(CharArray(payloadBytes) { 'a' + ((hash + it).mod(26L)).toInt() }.concatToString())))

/** A raw connection on the store's file, as a sibling process would hold. The caller closes it. */
internal fun rawConnection(databasePath: String): SqliteConnection =
    SqliteConnection.open(databasePath).also { it.exec("PRAGMA busy_timeout = 50") }

/** Records the store's own file-system calls, in order, and can fail one by name. */
internal class RecordingFiles(private val real: ReplicaFiles = PosixReplicaFiles) : ReplicaFiles {
    val calls = ArrayList<String>()
    var failOn: String? = null

    /** A lookup by path of the file with this name fails (not recorded in [calls]). */
    var failLookupOf: String? = null

    /** How many lookups of [failLookupOf] succeed before they start to fail. */
    var lookupsBeforeFailure = 0

    /** How many lookups were failed. */
    var lookupFailures = 0

    /** Called with every recorded call, before it is made. */
    var onCall: ((String) -> Unit)? = null
    private val descriptors = HashMap<Int, String>()

    /** Every descriptor handed out through this seam and not closed through it yet, by name. */
    val openDescriptors = HashMap<Int, String>()

    private fun record(call: String) {
        calls += call
        onCall?.invoke(call)
        if (failOn != null && call.startsWith(failOn!!)) throw ReplicaFileException("injected failure of $call")
    }

    /** The last path component, with a marker writer's own temporary suffix (pid, random) removed. */
    private fun name(path: String) = path.substringAfterLast('/').replace(Regex("\\.tmp\\.\\d+\\.[0-9a-f]+$"), ".tmp")

    override fun ensureDirectory(path: String) = real.ensureDirectory(path)
    override fun exists(path: String): Boolean {
        if (failLookupOf != null && name(path) == failLookupOf && lookupsBeforeFailure-- <= 0) {
            lookupFailures++
            throw ReplicaFileException("injected failure of the lookup of ${name(path)}")
        }
        return real.exists(path)
    }
    override fun createExclusive(path: String): Boolean {
        record("createExclusive ${name(path)}")
        return real.createExclusive(path)
    }

    override fun openReadOnly(path: String): Int = real.openReadOnly(path).also { opened(it, path) }
    override fun closeDescriptor(descriptor: Int) {
        openDescriptors.remove(descriptor)
        real.closeDescriptor(descriptor)
    }

    private fun opened(descriptor: Int, path: String) {
        descriptors[descriptor] = name(path)
        if (descriptor >= 0) openDescriptors[descriptor] = name(path)
    }
    override fun stat(descriptor: Int): ReplicaFileStat = real.stat(descriptor)
    override fun createAndWrite(path: String, bytes: ByteArray): Int {
        record("write ${name(path)}")
        return real.createAndWrite(path, bytes).also { opened(it, path) }
    }

    override fun remove(path: String) {
        if (failRemoveOf != null && name(path).startsWith(failRemoveOf!!)) return
        real.remove(path)
    }

    override fun fullSync(descriptor: Int) {
        record("fullSync ${descriptors[descriptor]}")
        real.fullSync(descriptor)
    }

    override fun rename(from: String, to: String) {
        record("rename ${name(from)} -> ${name(to)}")
        real.rename(from, to)
    }

    override fun readAll(path: String, maxBytes: Int): ByteArray? = real.readAll(path, maxBytes)

    override fun identity(path: String): String? = real.identity(path)

    /** Every `remove` of a file whose name starts with this does nothing, as an `unlink` that failed. */
    var failRemoveOf: String? = null

    override fun now(): Pair<Long, Long> = real.now()
}

/**
 * How many descriptors this process has open, whoever opened them: the store's own, SQLite's, the
 * runtime's. Counted by asking the kernel about each number, so nothing has to have gone through
 * a seam to be seen.
 */
internal fun openDescriptorCount(): Int {
    var open = 0
    for (descriptor in 0 until minOf(getdtablesize(), 8192)) {
        if (fcntl(descriptor, F_GETFD) != -1) open++
    }
    return open
}
