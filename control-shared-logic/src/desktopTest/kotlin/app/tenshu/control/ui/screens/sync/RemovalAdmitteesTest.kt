package app.tenshu.control.ui.screens.sync

import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.schemaProvider
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `admission-revocation-names-the-capability.md` §7j.4: removing a device lists what it admitted.
 * Since §7j the relay no longer kills a sleeper's chain when its admitter goes, so this list is
 * where the user sees one.
 */
class RemovalAdmitteesTest {

    private val context = createTestContext().also {
        (it.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
    }

    private fun device(id: String, admittedBy: String?) = Device.create(
        context = context,
        deviceId = id,
        name = "Device $id",
        publicKey = byteArrayOf(1),
        isAuthorized = true,
        admittedByDeviceId = admittedBy ?: "",
    )

    private fun ids(result: RemovalAdmittees) = result.admittees.map { it.deviceId.get() }

    @Test
    fun listsTheWholeLineage_nearestFirst() {
        val roster = listOf(
            device("phone", admittedBy = null),
            device("mac", admittedBy = "phone"),
            device("ipad", admittedBy = "mac"),
            device("tv", admittedBy = "ipad"),
            device("watch", admittedBy = "phone"),
        )
        assertEquals(listOf("ipad", "tv"), ids(removalAdmittees(roster, "mac", currentDeviceId = "phone")))
    }

    @Test
    fun neverOffersThisDevice_evenWhenTheRemovedOneAddedIt() {
        // The incident shape: the iPhone was admitted by the Mac, and the iPhone removes the Mac.
        val roster = listOf(device("mac", admittedBy = null), device("iphone", admittedBy = "mac"))
        assertEquals(emptyList(), ids(removalAdmittees(roster, "mac", currentDeviceId = "iphone")))
    }

    @Test
    fun countsTheRowsWhoseAdmitterIsUnknown_soTheListNeverClaimsToBeComplete() {
        // ⚠⚠ `admittedByDeviceId` is self-written: a sleeper can blank it and drop off the list.
        // The surface must say how many it could not place — and that one stays removable by id.
        val roster = listOf(
            device("phone", admittedBy = null),
            device("mac", admittedBy = "phone"),
            device("sleeper", admittedBy = ""),
            device("ipad", admittedBy = "mac"),
        )
        val result = removalAdmittees(roster, "mac", currentDeviceId = "phone")
        assertEquals(listOf("ipad"), ids(result))
        assertEquals(1, result.unlistedCount, "the blanked sleeper; this device is not counted")
    }

    @Test
    fun aSelfAdmissionIsNotALineage() {
        val roster = listOf(device("phone", admittedBy = null), device("mac", admittedBy = "mac"))
        assertEquals(emptyList(), ids(removalAdmittees(roster, "mac", currentDeviceId = "phone")))
    }

    @Test
    fun aRowNamingAnAdmitterNotInTheRoster_isCountedAsUnlisted() {
        // A fabricated (or departed) admitter id hides a row from the list just as a blank does.
        val roster = listOf(
            device("phone", admittedBy = null),
            device("mac", admittedBy = "phone"),
            device("sleeper", admittedBy = "fabricated-id"),
        )
        val result = removalAdmittees(roster, "mac", currentDeviceId = "phone")
        assertEquals(emptyList(), ids(result))
        assertEquals(1, result.unlistedCount)
    }
}
