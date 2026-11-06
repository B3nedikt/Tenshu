package org.tenshu.core.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract coverage for [mapChunksBounded], the bounded fan-out the chunked upload paths run
 * their transfers through.
 *
 * Three of its properties are load-bearing and each has a test here, because each one silently
 * breaking would surface far from this file: the **residency bound** (exceed it and the emit path
 * reintroduces the unbounded buffering that OOM-killed Control), **result ordering** (a manifest
 * whose chunk refs are permuted reassembles into a corrupt file), and **abort on failure** (a
 * transfer that keeps pushing bytes for a doomed manifest wastes the user's bandwidth). The
 * overlap test is the fourth: without it the whole change could regress to a sequential loop and
 * every other assertion here would still pass.
 */
class BoundedConcurrencyTest {

    /** Producer over a fixed item list — the shape every call site reduces to. */
    private fun <T> listProducer(items: List<T>): suspend (Int) -> T? = { i -> items.getOrNull(i) }

    @Test
    fun neverExceedsTheConfiguredConcurrency() = runTest {
        val concurrency = 3
        var inFlight = 0
        var peak = 0

        mapChunksBounded(
            concurrency = concurrency,
            produceNext = listProducer((1..20).toList()),
        ) { _, item ->
            inFlight++
            if (inFlight > peak) peak = inFlight
            // Stagger completion so the window genuinely fills rather than each transfer
            // finishing before the next is produced.
            delay((item % 5) * 10L + 1L)
            inFlight--
        }

        assertEquals(concurrency, peak, "the window should fill to exactly the permit count")
        assertTrue(inFlight == 0, "every permit must be released")
    }

    @Test
    fun boundsResidencyByTakingThePermitBeforeProducing() = runTest {
        // The bound only holds if production waits for a free slot. Counting *live produced
        // items* (not in-flight transfers) is what distinguishes acquire-before-produce from
        // acquire-before-transfer: the latter lets the producer read the whole file into heap
        // while transfers drain, which is the failure this ordering exists to prevent.
        var live = 0
        var peakLive = 0

        mapChunksBounded(
            concurrency = 2,
            produceNext = { index ->
                if (index >= 12) null
                else {
                    live++
                    if (live > peakLive) peakLive = live
                    index
                }
            },
        ) { _, _ ->
            delay(10)
            live--
        }

        assertEquals(2, peakLive, "at most `concurrency` produced chunks may be resident at once")
    }

    @Test
    fun preservesProductionOrderDespiteOutOfOrderCompletion() = runTest {
        val items = (0 until 8).toList()
        val completionOrder = mutableListOf<Int>()

        val results = mapChunksBounded(
            concurrency = 8, // whole batch in flight at once, so completion order is purely the delay
            produceNext = listProducer(items),
        ) { index, item ->
            // Later chunks finish first — the exact reordering a real network produces, made
            // deterministic by runTest's virtual clock.
            delay((items.size - index) * 10L)
            completionOrder += item
            "chunk-$item"
        }

        assertEquals(items.reversed(), completionOrder, "the test's premise: completion WAS reordered")
        assertEquals(items.map { "chunk-$it" }, results, "results must still be in production order")
    }

    @Test
    fun transfersActuallyOverlap() = runTest {
        // A sequential loop over 6 × 100ms takes 600ms of virtual time; with 3 in flight it
        // takes ~200ms. Asserting on the clock is what makes this a test of *concurrency*
        // rather than of bookkeeping.
        val start = testScheduler.currentTime
        mapChunksBounded(
            concurrency = 3,
            produceNext = listProducer((0 until 6).toList()),
        ) { _, _ -> delay(100) }
        val elapsed = testScheduler.currentTime - start

        assertEquals(200L, elapsed, "6 transfers of 100ms at concurrency 3 should take 200ms")
    }

    @Test
    fun concurrencyOfOneIsExactlySequential() = runTest {
        // The kill-switch has to be a true degradation, not an approximation of one.
        val order = mutableListOf<String>()

        mapChunksBounded(
            concurrency = 1,
            produceNext = { index ->
                if (index >= 4) null else index.also { order += "produce-$it" }
            },
        ) { index, _ ->
            delay(10)
            order += "transfer-$index"
        }

        assertEquals(
            listOf(
                "produce-0", "transfer-0",
                "produce-1", "transfer-1",
                "produce-2", "transfer-2",
                "produce-3", "transfer-3",
            ),
            order,
        )
    }

    @Test
    fun aFailedTransferAbortsTheBatchAndCancelsSiblings() = runTest {
        val started = mutableListOf<Int>()
        var completed = 0

        assertFailsWith<IllegalStateException> {
            mapChunksBounded(
                concurrency = 4,
                produceNext = listProducer((0 until 20).toList()),
            ) { index, _ ->
                started += index
                if (index == 1) {
                    delay(5)
                    error("transfer $index failed")
                }
                delay(1_000)
                completed++
            }
        }

        assertTrue(
            started.size < 20,
            "the failure must stop production, not merely fail after every chunk was sent " +
                "(started ${started.size} of 20)",
        )
        assertEquals(0, completed, "siblings still in flight must be cancelled, not awaited")
    }

    @Test
    fun aProducerFailurePropagatesAndReleasesItsPermit() = runTest {
        // A throwing producer must not strand the permit it took before calling us — otherwise a
        // retry of the enclosing operation would deadlock against a semaphore that never refills.
        assertFailsWith<IllegalStateException> {
            mapChunksBounded(
                concurrency = 1,
                produceNext = { index -> if (index == 2) error("read failed") else index },
            ) { _, _ -> delay(1) }
        }

        // Proof the helper is reusable afterwards: a fresh call with the same concurrency runs.
        val results = mapChunksBounded(
            concurrency = 1,
            produceNext = listProducer(listOf("a", "b")),
        ) { _, item -> item.uppercase() }
        assertEquals(listOf("A", "B"), results)
    }

    @Test
    fun anEmptyStreamDoesNoWork() = runTest {
        var transfers = 0
        val results = mapChunksBounded<Int, Unit>(
            concurrency = 4,
            produceNext = { null },
        ) { _, _ -> transfers++ }

        assertEquals(0, transfers)
        assertTrue(results.isEmpty())
    }

    @Test
    fun rejectsANonPositiveConcurrency() = runTest {
        assertFailsWith<IllegalArgumentException> {
            mapChunksBounded(concurrency = 0, produceNext = { null }) { _, _: Any -> Unit }
        }
    }

}
