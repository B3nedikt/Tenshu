package org.tenshu.routine.proxy

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.HubManagementService
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.RosterDevice

/**
 * Builds RE-D2's roster from realm data this device already holds (RE-3).
 *
 * ⭐ **A local read, not a negotiation.** The claim is a pure function over the
 * synced device roster, so every converged device computes the same executor
 * with zero coordination messages. This class is the whole "synced roster" half
 * of that sentence.
 *
 * Two sources, joined:
 *  - `Device` rows (`:identity-service`) — who is in the realm, and whether they
 *    are still authorised;
 *  - `HubStatus` rows (`:hub-management-service`) — which of them is a hub, and
 *    therefore always-on, resident and punctual.
 *
 * ⚠⚠ **Tier derivation is deliberately coarse: hub or not.** `Device` carries no
 * platform or power signal, and adding one would reshape a *shipped* composite
 * (peer-breaking, HR#9). So a hub is [CapabilityTier.ALWAYS_ON] and everything
 * else is [CapabilityTier.RESIDENT], with RE-D2's stable `deviceId` tiebreak
 * choosing among the rest.
 *
 * That is **correct but not optimal**, and the difference is worth naming: with
 * no hub, the winner may be a phone that is usually asleep, so routines fire on
 * the 90-second grace fallback rather than on time. RE-D2 already describes that
 * degradation for a stale winner ("everything runs 90 s late") — it is visible
 * and self-explaining, not silent. Distinguishing a resident desktop from a
 * mobile would need a new capability signal (a field on `Device`, or a small
 * per-device row like `HubStatus`), which is a schema decision of its own.
 */
internal class RoutineRosterSource(
    private val executionContext: ExecutionContext,
) {

    fun roster(): List<RosterDevice> {
        val hubIds = hubStatuses().map { status -> status.deviceId.get() }.toSet()
        return devices().map { device ->
            val id = device.deviceId.get()
            RosterDevice(
                deviceId = id,
                tier = if (id in hubIds) CapabilityTier.ALWAYS_ON else CapabilityTier.RESIDENT,
                // ⚠ A revoked device must not hold the claim — otherwise its
                // routines only ever fire on the fallback, forever. ADR 0007
                // revocation is the user-facing fix, and this is where it takes
                // effect for the engine.
                revoked = !device.isAuthorized.get(),
            )
        }
    }

    private fun devices(): List<Device> =
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = TList.ID,
                    relationId = IdentityService.Relations.DEVICES,
                ),
                isEntity = false,
                elementSchemaId = Device.ID,
            )
        )?.toList().orEmpty().filterIsInstance<Device>()

    private fun hubStatuses(): List<HubStatus> =
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = TList.ID,
                    relationId = HubManagementService.Relations.HUB_STATUSES,
                ),
                isEntity = false,
                elementSchemaId = HubStatus.ID,
            )
        )?.toList().orEmpty().filterIsInstance<HubStatus>()
}
