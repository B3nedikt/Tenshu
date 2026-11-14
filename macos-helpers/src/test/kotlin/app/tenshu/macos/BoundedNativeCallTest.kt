package app.tenshu.macos

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BoundedNativeCall] with a call that blocks on a latch, the stand-in for a downcall that never
 * returns — the one shape a real probe cannot be made to produce on demand.
 */
class BoundedNativeCallTest {

    @Test
    fun anAnswerInTimeIsReturned() {
        assertEquals(42, BoundedNativeCall("answers") { 42 }())
    }

    /** ⚠⚠ The hang this exists for: the caller comes back with unknown, the call stays stuck. */
    @Test
    fun aCallThatNeverReturnsAnswersUnknownWithinTheBound() {
        val release = CountDownLatch(1)
        val call = BoundedNativeCall<Int>("wedged", timeoutMs = 100) { release.await(); 1 }
        try {
            val started = System.nanoTime()
            assertNull(call())
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(tookMs < 5_000, "the caller waited ${tookMs}ms on a 100ms bound")
        } finally {
            release.countDown()
        }
    }

    /** ⚠ No queue behind a stuck call: one wedged function costs one thread, not one per caller. */
    @Test
    fun whileACallIsStuckLaterCallsAnswerUnknownAtOnceAndNeverRunIt() {
        val release = CountDownLatch(1)
        val runs = AtomicInteger(0)
        val call = BoundedNativeCall<Int>("stuck", timeoutMs = 100) {
            runs.incrementAndGet()
            release.await()
            1
        }
        try {
            assertNull(call())
            val started = System.nanoTime()
            repeat(50) { assertNull(call()) }
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(tookMs < 1_000, "50 calls behind a stuck one took ${tookMs}ms")
            assertEquals(1, runs.get(), "a later call queued behind the stuck one")
        } finally {
            release.countDown()
        }
    }

    /**
     * ⚠ Slow is not stuck: a second caller arriving during a cold first call gets the same answer,
     * not an early `null` (which the manual bar would read as "battery fine").
     */
    @Test
    fun aCallerArrivingWhileACallIsMerelySlowSharesItsAnswer() {
        val runs = AtomicInteger(0)
        val call = BoundedNativeCall("slow", timeoutMs = 5_000) {
            runs.incrementAndGet()
            Thread.sleep(300)
            9
        }
        val first = java.util.concurrent.CompletableFuture.supplyAsync { call() }
        Thread.sleep(50)
        assertEquals(9, call())
        assertEquals(9, first.get(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get(), "the second caller ran the call again")
    }

    @Test
    fun onceTheStuckCallReturnsTheNextCallAsksAgain() {
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val runs = AtomicInteger(0)
        val call = BoundedNativeCall<Int>("recovers", timeoutMs = 100) {
            if (runs.incrementAndGet() == 1) {
                release.await()
                returned.countDown()
            }
            7
        }
        assertNull(call())
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // The future is done a hair after the body returns; poll for it rather than assume.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var answer: Int? = null
        while (answer == null && System.nanoTime() < deadline) answer = call()
        assertEquals(7, answer)
    }

    @Test
    fun aThrowAnswersUnknown() {
        assertNull(BoundedNativeCall<Int>("throws") { error("no symbol") }())
    }
}
