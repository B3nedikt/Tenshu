package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.number.TInteger

/**
 * Wire DTO for [CompactionReceipt] (`core-schema-evolution.md` §8.3) —
 * materialised when a peer's receipt syncs in; callers build instances via
 * `CompactionReceipt.create(...)`.
 */
class CompactionReceiptDto(
    fieldValues: List<Data?>,
) : CompactionReceipt, CompositeDto(fieldValues) {

    override val receiptId: TString get() = getField(CompactionReceipt.Fields.RECEIPT_ID)
    override val serviceId: TString get() = getField(CompactionReceipt.Fields.SERVICE_ID)
    override val compactedToVersion: TInteger
        get() = getField(CompactionReceipt.Fields.COMPACTED_TO_VERSION)
    override val compactedBy: TString get() = getField(CompactionReceipt.Fields.COMPACTED_BY)
}
