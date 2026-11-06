package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto

/**
 * Wire DTO for [DeviceSchemaVersion] (`core-schema-evolution.md` §8.3, E4) —
 * materialised by the binary parser when a peer states what it runs;
 * cross-module callers build instances via `DeviceSchemaVersion.create(...)`.
 */
class DeviceSchemaVersionDto(
    fieldValues: List<Data?>,
) : DeviceSchemaVersion, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(DeviceSchemaVersion.Fields.DEVICE_ID)
    override val versionsBytes: TByteArray get() = getField(DeviceSchemaVersion.Fields.VERSIONS_BYTES)
}
