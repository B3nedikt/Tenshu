package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto

/**
 * Wire DTO for [RealmSchemaEpoch] (`core-schema-evolution.md` §7.1) —
 * materialised by the binary parser when a peer's epoch bump syncs in;
 * cross-module callers build instances via `RealmSchemaEpoch.create(...)`.
 */
class RealmSchemaEpochDto(
    fieldValues: List<Data?>,
) : RealmSchemaEpoch, CompositeDto(fieldValues) {

    override val epochId: TString get() = getField(RealmSchemaEpoch.Fields.EPOCH_ID)
    override val versionsBytes: TByteArray get() = getField(RealmSchemaEpoch.Fields.VERSIONS_BYTES)
}
