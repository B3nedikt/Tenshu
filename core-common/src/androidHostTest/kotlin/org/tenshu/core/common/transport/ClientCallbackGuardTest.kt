package org.tenshu.core.common.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app side of `IClientInterface` admits Control alone, and a `callbackShared` region only up to
 * the cap (threat model T-C-19). ⚠ The stub itself cannot be driven here — the host `android.jar`
 * has no working `Binder.getCallingUid()` or `SharedMemory` — so these pin the two decisions it
 * delegates to.
 */
class ClientCallbackGuardTest {

    private var now = 0L
    private var controlUid = CONTROL_UID
    private var lookups = 0
    private val guard = ClientCallbackGuard(isControlUid = { lookups++; it == controlUid }, nanoTime = { now })

    @Test
    fun controlsUidIsAdmittedAndNoOtherIs() {
        assertTrue(guard.admits(CONTROL_UID))
        assertFalse(guard.admits(STRANGER_UID))
        assertFailsWith<SecurityException> { guard.requireControl(STRANGER_UID, "callback") }
        guard.requireControl(CONTROL_UID, "callback")
    }

    /**
     * ⚠⚠ Found by the smoke gate (2026-10-23): with Control's UID resolved up front, a Control
     * reinstalled under a new UID — or one being installed at the moment of the lookup — was refused
     * for the whole re-check interval, and every stream element to Home bounced. The new UID must be
     * admitted on its first call.
     */
    @Test
    fun aReinstalledControlIsAdmittedOnItsFirstCall() {
        assertTrue(guard.admits(CONTROL_UID))

        controlUid = CONTROL_UID + 1
        assertTrue(guard.admits(CONTROL_UID + 1))
        assertEquals(2, lookups)
    }

    /** ⚠ Every stranger's call is a refusal; it must not be a package-manager call each time. */
    @Test
    fun aStrangerCannotMakeEveryCallALookup() {
        repeat(1_000) { assertFalse(guard.admits(STRANGER_UID)) }
        assertEquals(1, lookups)

        now += ClientCallbackGuard.RECHECK_INTERVAL_NANOS
        repeat(1_000) { assertFalse(guard.admits(STRANGER_UID)) }
        assertEquals(2, lookups, "at most one lookup per UID per interval")
    }

    @Test
    fun controlIsAnsweredFromTheCache() {
        repeat(1_000) { assertTrue(guard.admits(CONTROL_UID)) }
        assertEquals(1, lookups)
    }

    /**
     * ⚠ Below API 33 a UID can be reused after an uninstall: "this UID was Control" must not outlive
     * the Control it was. Dropped when the binding to Control dies, and re-verified at least every
     * [ClientCallbackGuard.CONTROL_TTL_NANOS] regardless.
     */
    @Test
    fun aUidThatWasControlIsAskedAgainOnceControlIsGone() {
        assertTrue(guard.admits(CONTROL_UID))

        // Control uninstalled; a stranger installed later is given the same UID.
        controlUid = -1
        guard.forgetControl()
        assertFalse(guard.admits(CONTROL_UID), "the binding died: the cached answer is dropped")
    }

    @Test
    fun aCachedControlUidIsReVerifiedAfterItsTtl() {
        assertTrue(guard.admits(CONTROL_UID))
        controlUid = -1 // reused without the binding ever reporting it

        now += ClientCallbackGuard.CONTROL_TTL_NANOS - 1
        assertTrue(guard.admits(CONTROL_UID), "within the TTL, answered from the cache")
        now += 1
        assertFalse(guard.admits(CONTROL_UID), "after it, asked again — and refused")
    }

    @Test
    fun aRegionOverTheCapIsRefusedBeforeAnythingIsAllocated() {
        ClientCallbackGuard.requireSharedMessageSize(1)
        ClientCallbackGuard.requireSharedMessageSize(BinderIpc.MAX_SHARED_MESSAGE_BYTES)
        assertFailsWith<SecurityException> {
            ClientCallbackGuard.requireSharedMessageSize(BinderIpc.MAX_SHARED_MESSAGE_BYTES + 1)
        }
        assertFailsWith<SecurityException> { ClientCallbackGuard.requireSharedMessageSize(0) }
        assertFailsWith<SecurityException> { ClientCallbackGuard.requireSharedMessageSize(Int.MAX_VALUE) }
    }

    private companion object {
        const val CONTROL_UID = 10_123
        const val STRANGER_UID = 10_456
    }
}
