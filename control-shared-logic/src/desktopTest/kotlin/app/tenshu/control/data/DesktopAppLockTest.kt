package app.tenshu.control.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Exercises the Kotlin glue of [DesktopAppLock] — the dormant fallback and the
 * helper token → [AppLockAuthResult] mapping — via a fake helper script. The real
 * `LocalAuthentication` round-trip is on-device / signed-build only.
 */
class DesktopAppLockTest {

    private val dir: File = Files.createTempDirectory("applock").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Writes an executable fake helper that prints [line] for every command (after draining stdin). */
    private fun fakeHelper(line: String): String {
        val script = File(dir, "helper-${line.hashCode()}.sh")
        script.writeText("#!/bin/sh\ncat >/dev/null 2>&1\necho '$line'\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    @Test
    fun dormant_whenNoHelperConfigured() = runBlocking {
        val lock = DesktopAppLock(helperPath = null)
        assertFalse(lock.isAvailable())
        assertEquals(AppLockAuthResult.Unavailable, lock.authenticate("Unlock Control"))
    }

    @Test
    fun dormant_whenHelperPathMissing() = runBlocking {
        assertFalse(DesktopAppLock(File(dir, "nope").absolutePath).isAvailable())
    }

    @Test
    fun isAvailable_reflectsHelperToken() = runBlocking {
        assertTrue(DesktopAppLock(fakeHelper("yes")).isAvailable())
        assertFalse(DesktopAppLock(fakeHelper("no")).isAvailable())
    }

    @Test
    fun authenticate_mapsTokens() = runBlocking {
        assertEquals(AppLockAuthResult.Success, DesktopAppLock(fakeHelper("ok"), raiseToForeground = raises()).authenticate("r"))
        assertEquals(AppLockAuthResult.Cancelled, DesktopAppLock(fakeHelper("cancelled"), raiseToForeground = raises()).authenticate("r"))
        assertEquals(AppLockAuthResult.Unavailable, DesktopAppLock(fakeHelper("unavailable"), raiseToForeground = raises()).authenticate("r"))
        assertEquals(AppLockAuthResult.Failure, DesktopAppLock(fakeHelper("failure:boom"), raiseToForeground = raises()).authenticate("r"))
    }

    /**
     * The raise policy — a sheet that was **presented** stole activation and must be given back,
     * whatever the user did with it. `applock-helper` has no bundle identity, so macOS does not
     * return activation on its own and Control's window is left behind the frontmost app, which
     * reads as the app closing (`control-keep-device-unlocked.md` §6.1).
     */
    @Test
    fun raisesToForeground_afterAnySheetThatWasPresented() = runBlocking {
        for (token in listOf("ok", "cancelled", "failure:boom")) {
            var raised = 0
            DesktopAppLock(fakeHelper(token)) { raised++ }.authenticate("r")
            assertEquals(1, raised, "a presented sheet ('$token') takes activation and must return it")
        }
    }

    /**
     * ⚠ The other half, and the one that would be a *worse* bug than the one being fixed: no sheet
     * was shown, so nothing took activation, and raising would be Control shoving itself in front of
     * the user unprompted. Covers both dormancy (no helper at all) and `LAContext` declining.
     */
    @Test
    fun doesNotRaise_whenNoSheetWasShown() = runBlocking {
        var raised = 0
        DesktopAppLock(fakeHelper("unavailable")) { raised++ }.authenticate("r")
        assertEquals(0, raised, "an unavailable backend shows nothing — nothing to raise back from")

        DesktopAppLock(helperPath = null) { raised++ }.authenticate("r")
        assertEquals(0, raised, "a dormant helper never runs, so it never takes activation")
    }

    /** Records what it was asked, so a test can tell which backend answered. */
    private class FakeInProcess(
        private val available: Boolean,
        private val result: AppLockAuthResult = AppLockAuthResult.Success,
    ) : InProcessLocalAuth {
        val reasons = mutableListOf<String>()
        override fun isAvailable() = available
        override fun authenticate(reason: String): AppLockAuthResult {
            reasons += reason
            return result
        }
    }

    /** The in-process check wins whenever it can show a sheet — the helper is never spawned. */
    @Test
    fun inProcess_isPreferredOverTheHelper() = runBlocking {
        val local = FakeInProcess(available = true, result = AppLockAuthResult.Cancelled)
        // A helper that would answer differently, so a fall-through cannot pass for the in-process path.
        val lock = DesktopAppLock(fakeHelper("ok"), inProcess = local, raiseToForeground = raises())
        assertTrue(lock.isAvailable())
        assertEquals(AppLockAuthResult.Cancelled, lock.authenticate("verify it's you"))
        assertEquals(listOf("verify it's you"), local.reasons)
    }

    /** In-process unavailable (no Touch ID policy, framework missing) ⇒ the helper still serves. */
    @Test
    fun inProcessUnavailable_fallsBackToTheHelper() = runBlocking {
        val local = FakeInProcess(available = false)
        val lock = DesktopAppLock(fakeHelper("ok"), inProcess = local, raiseToForeground = raises())
        assertEquals(AppLockAuthResult.Success, lock.authenticate("r"))
        assertEquals(emptyList(), local.reasons, "an unavailable in-process check must not be asked")
    }

    /** Available in-process alone makes the lock available — no helper needed (a bare `:run`). */
    @Test
    fun inProcessAvailable_withNoHelper_isAvailable() = runBlocking {
        assertTrue(DesktopAppLock(helperPath = null, inProcess = FakeInProcess(available = true)).isAvailable())
        assertFalse(DesktopAppLock(helperPath = null, inProcess = FakeInProcess(available = false)).isAvailable())
    }

    /** Same raise rule as the helper: a presented sheet raises, an unavailable one does not. */
    @Test
    fun inProcess_raisesOnlyAfterAPresentedSheet() = runBlocking {
        for (result in AppLockAuthResult.entries) {
            var raised = 0
            DesktopAppLock(
                helperPath = null,
                inProcess = FakeInProcess(available = true, result = result),
            ) { raised++ }.authenticate("r")
            val expected = if (result == AppLockAuthResult.Unavailable) 0 else 1
            assertEquals(expected, raised, "in-process $result")
        }
    }

    private class FakeEmbedded(
        private val available: Boolean = true,
        private val result: AppLockAuthResult = AppLockAuthResult.Success,
        private val blockUntilCancelled: Boolean = false,
    ) : EmbeddedLocalAuth {
        val released = CountDownLatch(1)
        var cancels = 0
        override fun isAvailable() = available
        var sawCancelled = false
        override fun authenticate(slot: EmbeddedAuthSlot, reason: String, isCancelled: () -> Boolean): AppLockAuthResult {
            if (blockUntilCancelled) released.await(10, TimeUnit.SECONDS)
            sawCancelled = isCancelled()
            return result
        }
        override fun move(slot: EmbeddedAuthSlot) = Unit
        override fun cancel() {
            cancels++
            released.countDown()
        }
    }

    private val slot = EmbeddedAuthSlot("Tenshu Control", 800.0, 600.0, 368.0, 300.0, 64.0, 64.0)

    /** No backend wired (every non-mac host, every test default): never available, answers Unavailable. */
    @Test
    fun embedded_withoutBackend_isUnavailable() = runBlocking {
        val lock = DesktopAppLock(helperPath = null)
        assertFalse(lock.embeddedAvailable())
        assertEquals(AppLockAuthResult.Unavailable, lock.authenticateEmbedded(slot, "r"))
    }

    /** The result passes through untouched, and nothing raises — no sheet ever left the window. */
    @Test
    fun embedded_passesTheResultThrough_andNeverRaises() = runBlocking {
        for (result in AppLockAuthResult.entries) {
            var raised = 0
            val lock = DesktopAppLock(helperPath = null, embedded = FakeEmbedded(result = result)) { raised++ }
            assertEquals(result, lock.authenticateEmbedded(slot, "r"), "embedded $result")
            assertEquals(0, raised, "embedded $result raised")
        }
    }

    /**
     * ⚠ Cancelling the coroutine must take the native view down: the blocked call only returns once
     * the context is invalidated, so without the `cancel()` "Use password" would wait out the timeout
     * with Touch ID still on screen.
     */
    @Test
    fun embedded_cancellingTheCoroutine_cancelsTheView() = runBlocking {
        val fake = FakeEmbedded(result = AppLockAuthResult.Cancelled, blockUntilCancelled = true)
        val lock = DesktopAppLock(helperPath = null, embedded = fake)
        val job = launch(Dispatchers.Default) { lock.authenticateEmbedded(slot, "r") }
        delay(100)
        val took = measureTimeMillis { job.cancelAndJoin() }
        assertEquals(1, fake.cancels)
        assertTrue(took < 5_000, "cancellation waited ${took}ms on the blocked call")
    }

    /**
     * ⚠⚠ A call cancelled while still queued (no context yet for `cancel()` to invalidate) must see
     * its own cancellation when it gets going — else it shows a view nobody is waiting on.
     */
    @Test
    fun aCallCancelledBeforeItStarts_seesItsOwnCancellation() = runBlocking {
        val fake = FakeEmbedded(result = AppLockAuthResult.Cancelled, blockUntilCancelled = true)
        val lock = DesktopAppLock(helperPath = null, embedded = fake)
        val job = launch(Dispatchers.Default) { lock.authenticateEmbedded(slot, "r") }
        delay(100)
        job.cancelAndJoin()
        assertTrue(fake.sawCancelled, "the call did not see that its caller had gone")
    }

    private fun raises(): () -> Unit = {}
}
