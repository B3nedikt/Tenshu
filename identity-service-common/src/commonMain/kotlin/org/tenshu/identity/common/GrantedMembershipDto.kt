package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.GrantedMembership

/**
 * Wire DTO for [GrantedMembership] (owner-side granted-member roster, GR-6).
 * Materialised by the binary parser when the row syncs across the owner's own
 * devices; cross-module callers build instances via `GrantedMembership.create(...)`.
 */
class GrantedMembershipDto(
    fieldValues: List<Data?>,
) : GrantedMembership, CompositeDto(fieldValues) {

    override val memberId: TString get() = getField(GrantedMembership.Fields.MEMBER_ID)
    override val realmId: TString get() = getField(GrantedMembership.Fields.REALM_ID)
    override val memberAuthorityPublicKey: TByteArray
        get() = getField(GrantedMembership.Fields.MEMBER_AUTHORITY_PUBLIC_KEY)
}
