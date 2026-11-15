package org.tenshu.sdk.ios

import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.reachability.UnreachableReason
import org.tenshu.storage.common.RealmStorageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `suite-sync-status.md` SS-1b: what iOS's sync strip says for each problem. */
class RealmStorageStripTest {

    /** ⚠⚠ The 2026-11-12 state: a device the relay no longer accepts, said in the app, with a way out. */
    @Test
    fun aDeviceTheRelayNoLongerAccepts_isSaid_asAnError_withOpenControl() {
        val text = assertNotNull(stripText(SuiteSyncProblem.DeviceUnrecognized))
        assertTrue(text.startsWith("This device isn't connected to your account any more"), text)
        assertTrue(text.contains("Don't use Forget this device first"), text)
        assertTrue(stripIsError(SuiteSyncProblem.DeviceUnrecognized))
        assertEquals("Open Control", stripActionLabel(SuiteSyncProblem.DeviceUnrecognized))
    }

    @Test
    fun everyProblem_hasWords_andNoneOrControlHasNone() {
        val problems = listOf(
            SuiteSyncProblem.Locked,
            SuiteSyncProblem.DeviceUnrecognized,
            SuiteSyncProblem.NoPlan,
            SuiteSyncProblem.StorageFull(RealmStorageState.Full(10, 10)),
            SuiteSyncProblem.StorageFull(null),
            SuiteSyncProblem.NeedsAttention,
            SuiteSyncProblem.Offline,
        )
        for (problem in problems) assertNotNull(stripText(problem), "$problem")
        assertNull(stripText(SuiteSyncProblem.None))
        // Control's banner is each Swift root's own; this strip never repeats it.
        assertNull(stripText(SuiteSyncProblem.Control(UnreachableReason.Transport)))
    }

    /** Offline and locked recover without a fault; no plan is not an error either. */
    @Test
    fun tones_andButtons() {
        assertFalse(stripIsError(SuiteSyncProblem.Offline))
        assertFalse(stripIsError(SuiteSyncProblem.Locked))
        assertFalse(stripIsError(SuiteSyncProblem.NoPlan))
        assertTrue(stripIsError(SuiteSyncProblem.StorageFull(null)))
        assertNull(stripActionLabel(SuiteSyncProblem.Offline), "offline recovers by itself")
        assertEquals("Manage storage", stripActionLabel(SuiteSyncProblem.NoPlan))
        assertEquals("Open Control", stripActionLabel(SuiteSyncProblem.Locked))
    }

    /** The figures still show when the storage row has them. */
    @Test
    fun aFullRealmWithFigures_saysHowFull() {
        val text = assertNotNull(stripText(SuiteSyncProblem.StorageFull(RealmStorageState.Full(2_000_000, 2_000_000))))
        assertTrue(text.startsWith("Storage full — "), text)
        assertTrue(text.contains(" of "), text)
    }
}
