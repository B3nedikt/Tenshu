@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EACCES
import platform.posix.EBADF
import platform.posix.EDQUOT
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.EIO
import platform.posix.ENODEV
import platform.posix.ENOSPC
import platform.posix.ENOTSUP
import platform.posix.ENOTTY
import platform.posix.EROFS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ReplicaFiles.fullSync` (§6's sidecar rule): `F_FULLFSYNC`, and `fsync` in its place **only**
 * where `F_FULLFSYNC` was refused as not supported. Any other failure of it is a failed sync.
 */
class ReplicaFullSyncTest {

    /** `F_FULLFSYNC` and `fsync` answering a chosen `errno` (0: success), and counting their calls. */
    private class Calls(private val fullFsyncAnswers: Int, private val fsyncAnswers: Int = 0) : ReplicaSyncCalls {
        var fullFsyncs = 0
        var fsyncs = 0

        override fun fullFsync(descriptor: Int): Int {
            fullFsyncs++
            return fullFsyncAnswers
        }

        override fun fsync(descriptor: Int): Int {
            fsyncs++
            return fsyncAnswers
        }
    }

    private val notSupported = listOf("ENOTSUP" to ENOTSUP, "ENOTTY" to ENOTTY)

    /** Everything else `F_FULLFSYNC` can fail with, the device node's `ENODEV` included. */
    private val realFailures = listOf(
        "EIO" to EIO, "ENOSPC" to ENOSPC, "EDQUOT" to EDQUOT, "EBADF" to EBADF, "EINTR" to EINTR,
        "EACCES" to EACCES, "EINVAL" to EINVAL, "EROFS" to EROFS, "ENODEV" to ENODEV,
    )

    @Test
    fun aFullSyncThatSucceedsCallsNothingElse() {
        val calls = Calls(fullFsyncAnswers = 0)
        PosixFiles(calls).fullSync(3)
        assertEquals(1, calls.fullFsyncs)
        assertEquals(0, calls.fsyncs)
    }

    @Test
    fun aFullSyncThatFailsForAnotherReasonThanNotSupportedIsAFailureAndFsyncIsNeverTried() {
        for ((name, code) in realFailures) {
            // fsync would succeed: it must not be asked.
            val calls = Calls(fullFsyncAnswers = code, fsyncAnswers = 0)
            val failure = assertFailsWith<ReplicaFileException>(name) { PosixFiles(calls).fullSync(3) }
            assertEquals(0, calls.fsyncs, "$name: fsync must not stand in for a flush that failed")
            assertTrue("errno=$code" in failure.message.orEmpty(), "$name: the failure names its errno: ${failure.message}")
        }
    }

    @Test
    fun aFullSyncRefusedAsNotSupportedFallsBackToFsync() {
        for ((name, code) in notSupported) {
            val calls = Calls(fullFsyncAnswers = code, fsyncAnswers = 0)
            PosixFiles(calls).fullSync(3)
            assertEquals(1, calls.fsyncs, name)
        }
    }

    @Test
    fun theFallbackFsyncFailingIsAFailure() {
        for ((name, code) in notSupported) {
            val calls = Calls(fullFsyncAnswers = code, fsyncAnswers = EIO)
            val failure = assertFailsWith<ReplicaFileException>(name) { PosixFiles(calls).fullSync(3) }
            assertTrue("errno=$EIO" in failure.message.orEmpty(), "$name: ${failure.message}")
        }
    }

    /**
     * The two real answers the rule rests on, on this machine: the store's own directory (APFS)
     * accepts `F_FULLFSYNC`, and a directory of a file system that does not implement it (devfs)
     * refuses it with `ENOTSUP` while accepting `fsync`.
     */
    @Test
    fun theKernelAcceptsFullSyncOnTheStoresDirectoryAndRefusesItAsNotSupportedOnDevfs() {
        val store = PosixReplicaFiles.openReadOnly(tempReplica())
        val devfs = PosixReplicaFiles.openReadOnly("/dev")
        try {
            assertEquals(0, PosixSyncCalls.fullFsync(store), "F_FULLFSYNC on a directory of the store's volume")
            assertEquals(ENOTSUP, PosixSyncCalls.fullFsync(devfs), "F_FULLFSYNC on a devfs directory")
            assertEquals(0, PosixSyncCalls.fsync(devfs), "fsync on a devfs directory")
            PosixReplicaFiles.fullSync(store)
            PosixReplicaFiles.fullSync(devfs)
        } finally {
            PosixReplicaFiles.closeDescriptor(store)
            PosixReplicaFiles.closeDescriptor(devfs)
        }
    }

    // ── what a caller of the marker then sees ──

    @Test
    fun aMarkerWhoseFlushFailedIsNotWrittenAndTheOpenIsARetry() {
        val directory = tempReplica()
        PosixReplicaFiles.createExclusive("$directory/objects.db-wal")
        // fsync would succeed on every descriptor here; the flush to the medium fails.
        val files = PosixFiles(Calls(fullFsyncAnswers = EIO))
        assertFailsWith<StoreUnavailable> { ReplicaProcess(directory, files = files).open() }
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"), "no marker was renamed into place")
    }

    @Test
    fun aMarkerFoundWhoseDirectoryFlushFailedIsNotDurable() {
        val directory = tempReplica()
        PosixReplicaFiles.closeDescriptor(
            PosixReplicaFiles.createAndWrite("$directory/${ReplicaCorruptMarker.NAME}", "app=sibling".encodeToByteArray()),
        )
        val failing = ReplicaCorruptMarker(PosixFiles(Calls(fullFsyncAnswers = EIO)), directory)
        assertEquals(ReplicaMarkerState.NOT_DURABLE, failing.state())
        failing.close()
        assertFailsWith<ReplicaFileException> { ReplicaCorruptMarker(PosixFiles(Calls(fullFsyncAnswers = EIO)), directory).write("x") }

        // Where the volume does not implement it, fsync is the sync, and the marker is durable.
        val unsupported = ReplicaCorruptMarker(PosixFiles(Calls(fullFsyncAnswers = ENOTSUP)), directory)
        assertEquals(ReplicaMarkerState.DURABLE, unsupported.state())
        unsupported.close()
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, files = PosixFiles(Calls(fullFsyncAnswers = ENOTSUP))).open())
    }
}
