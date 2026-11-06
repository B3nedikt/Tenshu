package org.tenshu.sync.proxy

import org.tenshu.core.common.util.encodeAdmissionStamp
import org.tenshu.sync.common.SyncManager

/**
 * The stamp this device sends with an admission it is authoring: *"I admitted this device at this
 * HLC."*
 *
 * The relay records it and refuses a `Device` tombstone authored earlier — see
 * `DeviceRegistry.revokeIfNotStale`. Without it a retirement that could not be pushed for hours
 * (401 against a wiped relay, 402 without an entitlement) still revoked a device that had been
 * legitimately re-admitted in the meantime, because delivery order is not authorship order.
 *
 * ⚠ [org.tenshu.core.common.util.HLCManager.tick] rather than `current()`: authoring an admission
 * *is* a local event, and the stamp must dominate everything this device has already observed —
 * `current()` would re-use an earlier instant and could tie with a tombstone it has already seen.
 *
 * One helper rather than five call sites building the same string: the encoding is a wire contract
 * with the relay, and a hand-rolled second copy of a wire contract is precisely what drifts.
 */
internal fun SyncManager.admissionStamp(): String = hlcManager.tick().encodeAdmissionStamp()
