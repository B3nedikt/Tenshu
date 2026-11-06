package org.tenshu.file.common.impl.sync

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.sync.PathSyncOverrideEntry

/**
 * DTO for [PathSyncOverrideEntry] — the persisted form of a sync-policy override
 * (`pathPrefix` + a [org.tenshu.core.file.sync.SyncPolicy] constant field). Mirrors
 * `TripMemberDto`.
 */
internal class PathSyncOverrideEntryDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PathSyncOverrideEntry
