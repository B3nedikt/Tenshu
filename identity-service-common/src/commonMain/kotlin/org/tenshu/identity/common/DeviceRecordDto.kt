package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.DeviceRecord

/** Wire DTO for [DeviceRecord] (`sync-entity-identity.md` §5b R6). */
class DeviceRecordDto(
    fieldValues: List<Data?>,
) : DeviceRecord, CompositeDto(fieldValues)
