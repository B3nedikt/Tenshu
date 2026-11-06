package app.tenshu.control.inference

import org.tenshu.inference.proxy.catalog.ModelStoreRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The store's refusal on its way to Control's Models screen: pins that the refusal keeps its kind.
 *
 * ⚠⚠ Reduced to one Boolean, a lock that could not be created at all would read as "another
 * Control is running" and tell the user to quit a process that is not there.
 */
class ModelStoreAccessTest {

    @Test
    fun anOwnedStoreIsWritable() {
        assertNull(modelStoreAccessOf(null).refusal)
    }

    @Test
    fun eachRefusalKeepsItsKindAndTheLockFailureKeepsItsDetail() {
        val contended = modelStoreAccessOf(ModelStoreRefusal.OwnedElsewhere("another Control owns it"))
        assertEquals(ModelStoreAccessSource.Refusal.OwnedElsewhere, contended.refusal)

        val unavailable = modelStoreAccessOf(
            ModelStoreRefusal.LockUnavailable("could not lock", detail = "/p/store.lock: IOException: denied"),
        )
        assertEquals(ModelStoreAccessSource.Refusal.LockUnavailable, unavailable.refusal)
        assertEquals("/p/store.lock: IOException: denied", unavailable.detail)
    }
}
