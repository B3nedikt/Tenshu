@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The route-1 viability spike (Control-on-iOS §C4)** — what the free simulator *can* settle about
 * the fork's hard question ("is cross-process single-writer HLC coordination tractable on iOS?"), and
 * what it provably cannot.
 *
 * Settled here, free:
 *  - **The coordination algorithm** — serialising a read-modify-write of shared clock state through a
 *    single lock yields a *fork-free, gap-free* HLC sequence even under heavy concurrency
 *    ([coordinatedReadModifyWrite_underOneLock_isForkFree]); and *without* serialisation the same
 *    writers fork ([withoutCoordination_forks]). The shared-file I/O the replica would use is exercised
 *    end-to-end.
 *  - **The lock primitive's API** — [FileAdvisoryLock] over `flock` detects conflicts
 *    ([advisoryLock_apiDetectsConflicts]).
 *
 * NOT settleable in a single-process unit test — discovered by running this spike: `flock`'s
 * *same-process, cross-thread* semantics differ from its cross-process semantics (a blocking
 * `flock(LOCK_EX)` from two threads of one process does **not** serialise them, so threads are an
 * invalid stand-in for processes). Therefore the *cross-process* exclusion `FileAdvisoryLock` is built
 * for — the actual route-1 mechanism — needs a genuine **two-process / on-device** check (the
 * paid-tier / device step, C4e). The in-process algorithm proof below uses a coroutine [Mutex] as the
 * serialiser precisely because it's a faithful in-process mutex, where `flock` is not.
 *
 * Net: the algorithm and the file substrate are de-risked for free; the remaining unknown is narrowed
 * to "does the cross-process lock reliably block on a real device" — a well-understood POSIX question,
 * not a novel risk.
 */
class CrossProcessHlcCoordinationSpikeTest {

    @Test
    fun advisoryLock_apiDetectsConflicts() {
        val dir = tempDir()
        val lockPath = "$dir/coord.lock"
        val a = FileAdvisoryLock.open(lockPath)
        val b = FileAdvisoryLock.open(lockPath)

        a.lock()
        assertFalse(b.tryLock(), "a second handle cannot acquire while the first holds the lock")
        a.unlock()
        assertTrue(b.tryLock(), "acquirable once the holder releases")
        b.unlock()

        a.close()
        b.close()
    }

    @Test
    fun coordinatedReadModifyWrite_underOneLock_isForkFree() {
        val dir = tempDir()
        val tipPath = "$dir/hlc.tip"
        writeTip(tipPath, 0L)

        val serialiser = Mutex() // a faithful in-process serialiser (see class KDoc on why not flock here)
        val writers = 8
        val ticksEach = 50
        val issued: List<Long> = runBlocking {
            (0 until writers).map {
                async(Dispatchers.Default) {
                    val mine = ArrayList<Long>(ticksEach)
                    repeat(ticksEach) {
                        serialiser.withLock {
                            val next = readTip(tipPath) + 1L
                            writeTip(tipPath, next)
                            mine.add(next)
                        }
                    }
                    mine
                }
            }.awaitAll().flatten()
        }

        val total = writers * ticksEach
        assertEquals(total, issued.size)
        // No fork (every tip unique) AND no lost update (the set is exactly 1..total).
        assertEquals((1L..total.toLong()).toList(), issued.sorted())
    }

    @Test
    fun withoutCoordination_forks() {
        // Why a serialiser is load-bearing — shown deterministically (no thread race). Two writers read
        // the shared tip in the un-serialised window and both compute the same next value: a fork.
        val dir = tempDir()
        val tipPath = "$dir/hlc.tip"
        writeTip(tipPath, 5L)

        val aRead = readTip(tipPath) // 5
        val bRead = readTip(tipPath) // 5 — read before a wrote back
        writeTip(tipPath, aRead + 1L) // a -> 6
        writeTip(tipPath, bRead + 1L) // b -> 6, a's update lost

        assertEquals(aRead + 1L, bRead + 1L, "un-serialised read-modify-write forks: both issue tip 6")
        assertEquals(6L, readTip(tipPath), "and one update is lost")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "hlc-spike-" + NSProcessInfo.processInfo.globallyUniqueString
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    private fun readTip(path: String): Long =
        (NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String?)
            ?.trim()
            ?.toLongOrNull()
            ?: 0L

    private fun writeTip(path: String, value: Long) {
        NSString.create(string = value.toString())
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
