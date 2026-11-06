package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.MemberAuthorityKey

/**
 * Wire DTO for [MemberAuthorityKey] (A26 storage). Materialised by the binary
 * parser when the row syncs across the member's own devices; cross-module
 * callers build instances via `MemberAuthorityKey.create(...)`.
 */
class MemberAuthorityKeyDto(
    fieldValues: List<Data?>,
) : MemberAuthorityKey, CompositeDto(fieldValues) {

    override val sharedRealmId: TString get() = getField(MemberAuthorityKey.Fields.SHARED_REALM_ID)
    override val publicKey: TByteArray get() = getField(MemberAuthorityKey.Fields.PUBLIC_KEY)
    override val privateKey: TByteArray get() = getField(MemberAuthorityKey.Fields.PRIVATE_KEY)
    override val capability: TByteArray get() = getField(MemberAuthorityKey.Fields.CAPABILITY)
}
