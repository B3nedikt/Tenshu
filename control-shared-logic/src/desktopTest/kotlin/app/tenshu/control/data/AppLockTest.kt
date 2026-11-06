package app.tenshu.control.data

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the soft app-lock's pure logic
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2): the device-local
 * [AppLockSettings] preference and the [AppLockController] gate state machine.
 * Neither touches key material — the data plane stays headless.
 */
class AppLockTest {

    @Test
    fun requireUnlock_defaultsOn_andPersists() {
        val storage = InMemorySecureStorageDelegate()

        val settings = AppLockSettings(storage)
        assertTrue(settings.requireUnlock.value, "default is on (privacy-first)")

        settings.setRequireUnlock(false)
        assertFalse(settings.requireUnlock.value)

        // A fresh instance over the same storage reads the persisted choice.
        assertFalse(AppLockSettings(storage).requireUnlock.value)
    }

    @Test
    fun controller_locksOnColdStart_whenRequireUnlockOn() {
        val controller = AppLockController(AppLockSettings(InMemorySecureStorageDelegate()))
        assertTrue(controller.locked.value, "a cold start with require-unlock on prompts")
    }

    @Test
    fun controller_neverLocks_whenRequireUnlockOff() {
        val storage = InMemorySecureStorageDelegate().apply {
            storeString("app_lock_require_unlock", "false")
        }
        val controller = AppLockController(AppLockSettings(storage))

        assertFalse(controller.locked.value)
        controller.onEnterBackground()
        assertFalse(controller.locked.value, "backgrounding never locks when the toggle is off")
    }

    @Test
    fun controller_unlockThenBackground_cyclesTheGate() {
        val controller = AppLockController(AppLockSettings(InMemorySecureStorageDelegate()))
        assertTrue(controller.locked.value)

        controller.onAuthenticated()
        assertFalse(controller.locked.value, "a successful prompt reveals the UI")

        controller.onEnterBackground()
        assertTrue(controller.locked.value, "going to background re-arms the gate")
    }

    /** Stands in for the platform probe; [available] flips like a user adding/removing a PIN. */
    private class FakeAvailability(var available: Boolean) : AppLockAvailability {
        override suspend fun isAvailable(): Boolean = available
    }

    @Test
    fun gate_passesThrough_onADeviceWithNoScreenLock() = runTest {
        // The 2026-10-30 lockout: require-unlock defaults ON, the device has no PIN at all, and
        // the gate used to wait for a prompt that could never succeed.
        val controller = AppLockController(AppLockSettings(InMemorySecureStorageDelegate()))
        assertTrue(controller.locked.value)

        assertTrue(controller.passIfUnenforceable(FakeAvailability(available = false)))
        assertFalse(controller.locked.value, "a device that cannot prompt must still open Control")
    }

    @Test
    fun gate_holds_whenTheDeviceCanPrompt() = runTest {
        val controller = AppLockController(AppLockSettings(InMemorySecureStorageDelegate()))

        assertFalse(controller.passIfUnenforceable(FakeAvailability(available = true)))
        assertTrue(controller.locked.value, "with a screen lock the gate waits for the prompt")
    }

    @Test
    fun gate_followsTheScreenLock_acrossReArms_neverLatched() = runTest {
        val controller = AppLockController(AppLockSettings(InMemorySecureStorageDelegate()))
        val device = FakeAvailability(available = true)

        // Had a PIN: the prompt clears the gate.
        assertFalse(controller.passIfUnenforceable(device))
        controller.onAuthenticated()

        // The user removes the screen lock, then Control is backgrounded and reopened.
        device.available = false
        controller.onEnterBackground()
        assertTrue(controller.locked.value)
        assertTrue(controller.passIfUnenforceable(device), "a removed screen lock is not a lockout")
        assertFalse(controller.locked.value)

        // And sets one again: the next re-arm gates once more.
        device.available = true
        controller.onEnterBackground()
        assertFalse(controller.passIfUnenforceable(device))
        assertTrue(controller.locked.value, "a screen lock added later is honoured")
    }

    @Test
    fun passThrough_neverLocksAnUnlockedGate() = runTest {
        val storage = InMemorySecureStorageDelegate().apply {
            storeString("app_lock_require_unlock", "false")
        }
        val controller = AppLockController(AppLockSettings(storage))

        assertFalse(controller.passIfUnenforceable(FakeAvailability(available = false)))
        assertFalse(controller.locked.value)
    }

    @Test
    fun prompt_usesWeakBiometric_onlyWhereStrongPlusCredentialIsUnsupported() {
        // AndroidX: BIOMETRIC_STRONG | DEVICE_CREDENTIAL is unsupported on API 28–29 only.
        assertFalse(appLockPromptNeedsWeakBiometric(24))
        assertFalse(appLockPromptNeedsWeakBiometric(27))
        assertTrue(appLockPromptNeedsWeakBiometric(28))
        assertTrue(appLockPromptNeedsWeakBiometric(29))
        assertFalse(appLockPromptNeedsWeakBiometric(30))
        assertFalse(appLockPromptNeedsWeakBiometric(36))
    }
}
