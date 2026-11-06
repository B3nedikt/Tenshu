package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.OwnedRealmMaster

/**
 * Wire DTO for [OwnedRealmMaster] (owner-side realm master A1 custody, GR-6).
 * Materialised by the binary parser when the row syncs across the owner's own
 * devices; cross-module callers build instances via `OwnedRealmMaster.create(...)`.
 */
class OwnedRealmMasterDto(
    fieldValues: List<Data?>,
) : OwnedRealmMaster, CompositeDto(fieldValues) {

    override val sharedRealmId: TString get() = getField(OwnedRealmMaster.Fields.SHARED_REALM_ID)
    override val masterPublicKey: TByteArray get() = getField(OwnedRealmMaster.Fields.MASTER_PUBLIC_KEY)
    override val masterPrivateKey: TByteArray get() = getField(OwnedRealmMaster.Fields.MASTER_PRIVATE_KEY)
}
