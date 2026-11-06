package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.AdmissionKeyAnnouncement

/**
 * Wire DTO for [AdmissionKeyAnnouncement] (A33 migration announcement, P4).
 * Materialised by the binary parser when the row syncs across realm devices;
 * callers build instances via `AdmissionKeyAnnouncement.create(...)`.
 */
class AdmissionKeyAnnouncementDto(
    fieldValues: List<Data?>,
) : AdmissionKeyAnnouncement, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(AdmissionKeyAnnouncement.Fields.DEVICE_ID)
    override val publicKey: TByteArray get() = getField(AdmissionKeyAnnouncement.Fields.PUBLIC_KEY)
    override val signature: TByteArray get() = getField(AdmissionKeyAnnouncement.Fields.SIGNATURE)
}
