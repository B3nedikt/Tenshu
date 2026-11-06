package app.tenshu.timeline.data.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ios-shared-store-sqlite.md` Appendix B.15 (210): the capture's queued tasks run inside one entry
 * (one shared-replica token) per burst, never one per task: a release per task would quiesce per
 * task in the background. Every task is made up.
 */
class CaptureEntryDrainTest {

    @Test
    fun aBurstOfQueuedTasksRunsInsideOneEntryInOrder() = runBlocking {
        val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val ran = ArrayList<Int>()
        var entries = 0
        repeat(8) { i -> work.trySend { ran += i } }
        work.close()
        drainInEntries(work, entry = { drain ->
            entries++
            drain()
        }, idleMs = 50)
        assertEquals((0 until 8).toList(), ran)
        assertEquals(1, entries, "one token for the whole burst")
    }

    @Test
    fun aTaskArrivingWithinTheIdleWindowJoinsTheEntryAndOneAfterItStartsAnother() = runBlocking {
        val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val ran = ArrayList<Int>()
        var entries = 0
        val drainer = launch {
            drainInEntries(work, entry = { drain ->
                entries++
                drain()
            }, idleMs = 300)
        }
        work.send { ran += 1 }
        delay(50)
        work.send { ran += 2 } // inside the idle window: the same entry
        delay(1_000)
        work.send { ran += 3 } // after it: a new entry
        delay(100)
        work.close()
        drainer.join()
        assertEquals(listOf(1, 2, 3), ran)
        assertEquals(2, entries)
    }

    @Test
    fun aTaskSentAtTheIdleWindowsDeadlineIsNeverLost() = runBlocking {
        // A receive cancelled by the window's timeout after it had been handed
        // an element drops that element. Raced in a loop: a send timed at about the deadline,
        // from another thread, must run exactly once, whichever side of the deadline it lands.
        val idleMs = 3L
        repeat(300) { round ->
            val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
            val ran = AtomicInt(0)
            val drainer = launch(Dispatchers.Default) {
                drainInEntries(work, entry = { drain -> drain() }, idleMs = idleMs)
            }
            work.send { ran.incrementAndGet() }
            val sender = launch(Dispatchers.Default) {
                delay(idleMs - 1 + Random.nextLong(3))
                work.send { ran.incrementAndGet() }
            }
            sender.join()
            work.close()
            drainer.join()
            assertEquals(2, ran.value, "round $round: every task ran exactly once")
        }
    }
}
