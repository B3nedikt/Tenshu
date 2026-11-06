package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.SharedRealmMembership

/**
 * Wire DTO for [SharedRealmMembership]. Materialised by the binary parser when a
 * membership row arrives over sync; cross-module callers build instances via
 * `SharedRealmMembership.create(...)`, never this class directly.
 */
class SharedRealmMembershipDto(
    fieldValues: List<Data?>,
) : SharedRealmMembership, CompositeDto(fieldValues) {

    override val sharedRealmId: TString get() = getField(SharedRealmMembership.Fields.SHARED_REALM_ID)
    override val wrappedMasterKey: TByteArray get() = getField(SharedRealmMembership.Fields.WRAPPED_MASTER_KEY)
    override val role: TString get() = getField(SharedRealmMembership.Fields.ROLE)
}
