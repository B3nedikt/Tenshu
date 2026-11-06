package org.tenshu.file.common.impl.sync

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.sync.FileSyncSetting

/**
 * DTO for [FileSyncSetting] — mirrors `ShareDto` / `FileModificationDto`. The
 * derived per-device sync-settings projection; only the `fieldValues` ctor is
 * required (the proxy-side maintainer constructs it via `FileSyncSetting.create`).
 */
internal open class FileSyncSettingDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), FileSyncSetting
