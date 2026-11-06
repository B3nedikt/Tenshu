package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.FileContentManifestPayload

internal class FileContentManifestPayloadDto(
    fieldValues: List<Data?>
) : FileContentManifestPayload, CompositeDto(fieldValues)
