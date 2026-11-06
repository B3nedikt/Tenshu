package org.tenshu.core.common

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConcurrentMemo] is what `TenshuEnvironment.getService` keeps its services in, and on iOS route 1
 * that getter is called from several threads at once (`photos-ios.md` §8.22.2).
 *
 * ⚠ Seen red against a plain `mutableMapOf().getOrPut`: two threads that miss together each keep the
 * value they made, so callers of one key hold different objects.
 */
class ConcurrentMemoTest {

    @Test
    fun everyThreadGetsTheSameValueForAKey_whileOtherKeysAreBeingAdded() {
        repeat(200) { round ->
            val memo = ConcurrentMemo<String, Any>()
            val threads = 8
            val start = CyclicBarrier(threads)
            val seen = List(threads) { AtomicReference<List<Any>>() }
            val failure = AtomicReference<Throwable>()
            val workers = List(threads) { n ->
                thread {
                    try {
                        start.await()
                        // The shared keys every thread asks for, between keys only it adds — the
                        // inserts that grow the map under the others' lookups.
                        seen[n].set(
                            (0 until 40).map { key ->
                                memo.getOrPut("own-$n-$key") { Any() }
                                memo.getOrPut("shared-$key") { Any() }
                            },
                        )
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            }
            workers.forEach { it.join() }
            assertNull(failure.get(), "round $round")
            val first = seen.first().get()
            seen.forEachIndexed { n, values ->
                values.get().forEachIndexed { key, value ->
                    assertEquals(true, value === first[key], "round $round: thread $n holds its own value for shared-$key")
                }
            }
        }
    }

    @Test
    fun aValueIsMadeOnceWhenNobodyRaces() {
        val memo = ConcurrentMemo<String, Any>()
        var made = 0
        val a = memo.getOrPut("a") { made++; Any() }
        assertEquals(true, a === memo.getOrPut("a") { made++; Any() })
        assertEquals(1, made)
    }
}
