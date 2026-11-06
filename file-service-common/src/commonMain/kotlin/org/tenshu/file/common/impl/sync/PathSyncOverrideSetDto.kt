package org.tenshu.file.common.impl.sync

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.sync.PathSyncOverrideSet

/**
 * DTO for [PathSyncOverrideSet] — the single-composite wrapper around the override
 * list used as the `file.sync.overrides` preference value (the `genericData` slot
 * can't hold a bare array). Mirrors `FileSyncSettingDto`.
 */
internal class PathSyncOverrideSetDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PathSyncOverrideSet
