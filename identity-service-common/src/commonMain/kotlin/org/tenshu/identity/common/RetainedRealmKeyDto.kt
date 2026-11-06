package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.identity.RetainedRealmKey

/**
 * Wire DTO for [RetainedRealmKey] (the retained-keyring row, GR-7). Materialised by
 * the binary parser when the row syncs across the member's own devices; cross-module
 * callers build instances via `RetainedRealmKey.create(...)`.
 */
class RetainedRealmKeyDto(
    fieldValues: List<Data?>,
) : RetainedRealmKey, CompositeDto(fieldValues) {

    override val compositeKey: TString get() = getField(RetainedRealmKey.Fields.COMPOSITE_KEY)
    override val sharedRealmId: TString get() = getField(RetainedRealmKey.Fields.SHARED_REALM_ID)
    override val wrappedMasterKey: TByteArray get() = getField(RetainedRealmKey.Fields.WRAPPED_MASTER_KEY)
    override val retiredAt: UnixInstant get() = getField(RetainedRealmKey.Fields.RETIRED_AT)
}
