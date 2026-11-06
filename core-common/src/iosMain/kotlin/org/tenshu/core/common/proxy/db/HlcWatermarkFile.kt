@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.tenshu.core.common.proxy.db

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.usleep

/**
 * A durable, cross-process, **monotonically-raised** `(physicalTime, logicalCounter)` pair in a
 * file, guarded by [FileAdvisoryLock] (`sync-hlc-tip-device-identity.md` TC-2b).
 *
 * ## What it is for
 *
 * Route 1 runs a runtime per first-party iOS app — Control, Drive, and the rest — each with its own
 * `HLCManager`, all under **one** device identity from the shared Keychain access group. The relay
 * keeps a single HLC-tip slot per `(ownerId, deviceId)` and overwrites it unconditionally, so those
 * runtimes took turns publishing their own unrelated clock positions into it and peers read the
 * backward step as the T-T-04 forked-view tell. Observed 2026-08-28: an iPhone tip of 15:07
 * followed by one of 14:51. This file is where they agree on one position.
 *
 * ## ⚠⚠ Why this lives in `:core-common` rather than beside its only caller
 *
 * Its adapter (`IosSharedHlcWatermarkStore`, an `HlcWatermarkStore` in `:control-shared-proxy`)
 * cannot be tested: the root build disables `iosSimulatorArm64Test` — **and the test link** — for
 * every `-proxy` module, because that LLVM step is the heaviest K/N task and running it across ~19
 * modules caused recurring `Java heap space` OOMs. A mechanism that decides whether a device
 * publishes a monotone clock must not be the untestable half, so the mechanism is here, next to the
 * lock it wraps and inside a module `iosTestCheck` actually runs. What stays over there is a
 * ten-line type mapping.
 *
 * It deliberately does **not** know about `HlcWatermark` (`:sync-service-common`), which depends on
 * this module and not the other way round — hence [Stamp].
 *
 * ## ⚠⚠ The invariant a caller must not break: co-location
 *
 * Whoever constructs this must resolve [directory] the way the **object store's** directory is
 * resolved — [sharedReplicaDirectory] — so the file shares exactly when the replica shares. A
 * watermark shared more widely than the store it describes makes a device claim it observed HLCs
 * its store never saw, forging the tombstone-GC ack the relay acts on and dropping bodies that
 * device never applied. That is strictly worse than the bug this fixes.
 *
 * ⚠ Give it a **sibling** directory, never the store's own (`replica/`, whose files the store's
 * wipe and D14's deletion own).
 *
 * ## ⚠⚠ Why the lock is tried, never waited on
 *
 * `flock` is released when the holder's descriptor closes — process death included — but **not when
 * iOS merely suspends the holder**, which it may do at any moment, including mid-write. A blocking
 * [FileAdvisoryLock.lock] here would let a backgrounded Drive freeze Control's sync tick for as
 * long as the OS liked. So [raiseTo] tries with a bounded backoff and then falls back to a
 * lock-free merge that **persists nothing**: the caller still gets a value never below its own, and
 * the next poll writes it. "Correct but not yet persisted" is the right way to degrade; "correct
 * and hung" is not.
 *
 * Blocking, not suspending — the calls are a small read and an atomic write. Callers that may be on
 * the main thread should wrap it.
 */
class HlcWatermarkFile(
    private val directory: String,
) {

    /**
     * A watermark's two values. Ordered like an HLC — physical time, then logical counter — because
     * two runtimes on one device inside a single millisecond are exactly the case that matters, and
     * a physical-time-only comparison would wave it through.
     */
    data class Stamp(
        val physicalTime: Long,
        val logicalCounter: Long,
    ) : Comparable<Stamp> {

        override fun compareTo(other: Stamp): Int =
            if (physicalTime != other.physicalTime) physicalTime.compareTo(other.physicalTime)
            else logicalCounter.compareTo(other.logicalCounter)

        companion object {
            /** Nothing observed yet. A real tip is always > 0, so this is unambiguous. */
            val NONE = Stamp(0L, 0L)
        }
    }

    private val statePath get() = "$directory/$STATE_FILE"
    private val lockPath get() = "$directory/$LOCK_FILE"

    /** The stored stamp, or [Stamp.NONE]. Lock-free: a reader never blocks a writer. */
    fun read(): Stamp = decode(NSString.stringWithContentsOfFile(statePath, NSUTF8StringEncoding, null))

    /** Raises the stored stamp to at least [candidate] and returns the result. Never lowers. */
    fun raiseTo(candidate: Stamp): Stamp {
        ensureDirectory()
        repeat(LOCK_ATTEMPTS) {
            raiseUnderLock(candidate)?.let { return it }
            usleep(LOCK_BACKOFF_MICROS)
        }
        // Lock unavailable — see the class KDoc. Still never returns below `candidate`.
        return maxOf(read(), candidate)
    }

    /** One attempt; `null` when another runtime holds the lock, so the caller can back off. */
    private fun raiseUnderLock(candidate: Stamp): Stamp? {
        val lock = FileAdvisoryLock.open(lockPath)
        if (!lock.tryLock()) {
            lock.close()
            return null
        }
        return try {
            val current = read()
            // Write-on-advance: two idle apps polling every 30 s must not rewrite this file 240
            // times an hour between them.
            if (candidate <= current) current else candidate.also(::write)
        } finally {
            lock.unlock()
            lock.close()
        }
    }

    private fun ensureDirectory() {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private fun write(stamp: Stamp) {
        // Via NSData rather than `(text as NSString).writeToFile(...)`, the form
        // `SharedReplicaChoice.declare` uses. ⚠ The cast form compiles with "This cast can never succeed" and
        // that warning is **spurious** — measured 2026-08-30 on the simulator, Kotlin/Native
        // bridges String↔NSString and the write succeeds (`NSStringBridgingTest` pins it). So
        // this is not a correctness fix over the cast; NSData is chosen because it does not rest
        // on a bridging behaviour the compiler disputes, and it is the form already used by
        // `IosGetPreviewImplementation` and `DrivePreviewSurface`.
        //
        // `atomically` writes to a temp file and renames, so the lock-free reader above sees the
        // old value or the new one, never a half-written line.
        encode(stamp).encodeToByteArray().toNSData().writeToFile(statePath, atomically = true)
    }

    /** ByteArray → NSData, the one iOS write path known to work here. */
    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) {
            NSData()
        } else {
            memScoped { NSData.create(bytes = allocArrayOf(this@toNSData), length = size.convert()) }
        }

    companion object {
        /**
         * The subdirectory to pass [sharedReplicaDirectory] for this file — a sibling of the
         * store's `replica/`, resolved by the same function so its sharing tracks the replica's.
         */
        const val SUBDIRECTORY: String = "sync-hlc"

        internal const val STATE_FILE = "watermark.state"
        private const val LOCK_FILE = "watermark.lock"

        /** ~100 ms of total patience, then the lock-free fallback. Contention is two apps at most. */
        private const val LOCK_ATTEMPTS = 5
        private const val LOCK_BACKOFF_MICROS = 20_000u

        /**
         * `<physicalTime>:<logicalCounter>`. Absent or malformed decodes to [Stamp.NONE] rather
         * than throwing: this is an optimisation over a clock that still works without it, so a
         * corrupt file must cost the optimisation, never the launch.
         */
        internal fun encode(stamp: Stamp): String = "${stamp.physicalTime}:${stamp.logicalCounter}"

        internal fun decode(raw: String?): Stamp {
            val parts = raw?.trim()?.split(':') ?: return Stamp.NONE
            if (parts.size != 2) return Stamp.NONE
            val physical = parts[0].toLongOrNull() ?: return Stamp.NONE
            val counter = parts[1].toLongOrNull() ?: return Stamp.NONE
            if (physical < 0L || counter < 0L) return Stamp.NONE
            return Stamp(physical, counter)
        }
    }
}
