package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.AdmissionCertGrant

/**
 * Wire DTO for [AdmissionCertGrant] (A33 cert delivery, P4). Materialised by
 * the binary parser when the row syncs across realm devices; callers build
 * instances via `AdmissionCertGrant.create(...)`.
 */
class AdmissionCertGrantDto(
    fieldValues: List<Data?>,
) : AdmissionCertGrant, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(AdmissionCertGrant.Fields.DEVICE_ID)
    override val masterPublicKey: TByteArray get() = getField(AdmissionCertGrant.Fields.MASTER_PUBLIC_KEY)
    override val certChain: TByteArray get() = getField(AdmissionCertGrant.Fields.CERT_CHAIN)
}
