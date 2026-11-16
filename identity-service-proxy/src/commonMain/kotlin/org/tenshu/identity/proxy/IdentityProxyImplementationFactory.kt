package org.tenshu.identity.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.identity.GetDevices
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.GetRealmInvitations
import org.tenshu.identity.GetPendingAcceptances
import org.tenshu.identity.proxy.executor.GetPendingAcceptancesImplementation
import org.tenshu.identity.proxy.executor.GetRealmInvitationsImplementation
import org.tenshu.identity.GetSharedRealmMemberships
import org.tenshu.identity.proxy.executor.GetDevicesImplementation
import org.tenshu.identity.proxy.executor.GetRealmMembersImplementation
import org.tenshu.identity.proxy.executor.GetSharedRealmMembershipsImplementation

fun identityProxyImplementationFactory(id: String): OperationImplementation? {
    return when (id) {
        GetDevices.ID -> GetDevicesImplementation()
        GetSharedRealmMemberships.ID -> GetSharedRealmMembershipsImplementation()
        GetRealmMembers.ID -> GetRealmMembersImplementation()
        GetRealmInvitations.ID -> GetRealmInvitationsImplementation()
        GetPendingAcceptances.ID -> GetPendingAcceptancesImplementation()
        else -> null
    }
}
