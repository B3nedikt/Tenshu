package org.tenshu.sync.proxy

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every device in a process records into the one [EnvelopeRejections], each from its own ingest
 * coroutine on a multi-threaded dispatcher, so two devices pulling the same forged batch record at
 * the same instant. A `++` on a `@Volatile` field is a read-modify-write and drops increments under
 * that contention; `ForgedTombstoneSystemTest` waits for an exact count of them and timed out when
 * one was lost.
 */
class EnvelopeRejectionsConcurrencyTest {

    @Test
    fun concurrentRecordsAreAllCounted() {
        val threads = 8
        val perThread = 20_000
        val tombstonesBefore = EnvelopeRejections.tombstoneCount
        val blocksBefore = EnvelopeRejections.blockCount
        val start = CyclicBarrier(threads)

        (0 until threads).map { t ->
            thread {
                start.await()
                val kind = if (t % 2 == 0) EnvelopeRejections.Kind.TOMBSTONE_TAG else EnvelopeRejections.Kind.BLOCK_ENVELOPE
                repeat(perThread) { EnvelopeRejections.record(kind, "concurrency-test") }
            }
        }.forEach { it.join() }

        val each = (threads / 2).toLong() * perThread
        assertEquals(each, EnvelopeRejections.tombstoneCount - tombstonesBefore, "tombstone rejections lost")
        assertEquals(each, EnvelopeRejections.blockCount - blocksBefore, "block rejections lost")
    }
}
