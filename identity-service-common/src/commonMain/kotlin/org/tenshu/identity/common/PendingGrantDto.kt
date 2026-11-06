package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.identity.PendingGrant

/**
 * Wire DTO for [PendingGrant] (the quarantined inbound grant the poll stores, GR-6).
 * Materialised by the binary parser when the row syncs across the user's own
 * devices; cross-module callers build instances via `PendingGrant.create(...)`.
 */
class PendingGrantDto(
    fieldValues: List<Data?>,
) : PendingGrant, CompositeDto(fieldValues) {

    override val grantorFingerprint: TString get() = getField(PendingGrant.Fields.GRANTOR_FINGERPRINT)
    override val sealedGrant: TByteArray get() = getField(PendingGrant.Fields.SEALED_GRANT)
    override val address: TString get() = getField(PendingGrant.Fields.ADDRESS)
    override val discoveredAt: UnixInstant get() = getField(PendingGrant.Fields.DISCOVERED_AT)
}
