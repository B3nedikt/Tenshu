package app.tenshu.control.ui.screens.sync

import org.tenshu.identity.Device

/**
 * What removing a device should ask about: the devices it admitted
 * (`admission-revocation-names-the-capability.md` §7j.4).
 *
 * Since §7j the relay no longer refuses an approval because an *ancestor* of the approver was
 * removed, so a "sleeper" a thief admitted through a stolen device keeps its membership until the
 * user acts. This is where the user acts: the removal surface lists the removed device's admittees
 * and asks, per device, whether to keep trusting it.
 *
 * @property admittees the devices [Device.admittedByDeviceId] says the removed device admitted,
 *   transitively (an admittee's own admittees too — the lineage Q3's subtree strip walked
 *   before §7j.10 retired it), nearest first. Never the removed device itself, never this device.
 * @property unlistedCount devices in the roster (other than the removed one and this device) whose
 *   row records no admitter, or one that is not in the roster, so whether the removed device added
 *   them cannot be known here.
 *   ⚠⚠ `admittedByDeviceId` is self-written (whole-row LWW, no authorship check): a malicious
 *   device can blank it, or name a fabricated or departed id, and drop off this list — so the list is
 *   **advisory**, and a device that lies about its admitter can still be removed by id. It stays removable by id from the roster, which is
 *   why the surface says how many could not be placed instead of implying the list is complete.
 */
data class RemovalAdmittees(
    val admittees: List<Device>,
    val unlistedCount: Int,
)

/**
 * [RemovalAdmittees] for removing [removedDeviceId] from [devices], seen from [currentDeviceId].
 *
 * ⚠ This device is never offered for removal here even when the removed device admitted it —
 * removing yourself is *Forget this device*, with its own warnings.
 */
fun removalAdmittees(
    devices: List<Device>,
    removedDeviceId: String,
    currentDeviceId: String,
): RemovalAdmittees {
    val admitterOf = devices.associate { dev ->
        dev.deviceId.get() to dev.admittedByDeviceId?.get()?.takeIf { it.isNotEmpty() && it != dev.deviceId.get() }
    }
    val byId = devices.associateBy { it.deviceId.get() }
    val subtree = linkedSetOf<String>()
    val queue = ArrayDeque(listOf(removedDeviceId))
    while (queue.isNotEmpty()) {
        val parent = queue.removeFirst()
        for ((child, admitter) in admitterOf) {
            if (admitter == parent && child != removedDeviceId && subtree.add(child)) queue.addLast(child)
        }
    }
    subtree.remove(currentDeviceId)
    // ⚠⚠ "Unplaceable" is not only a blank admitter: the field is self-written, so a device can
    // name an id that is in no roster at all, and drop off the list without being blank. Count both.
    val unlisted = admitterOf.count { (id, admitter) ->
        (admitter == null || admitter !in byId) && id != removedDeviceId && id != currentDeviceId
    }
    return RemovalAdmittees(admittees = subtree.mapNotNull { byId[it] }, unlistedCount = unlisted)
}
