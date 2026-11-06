package org.tenshu.file.common.impl.share

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.share.ShareLinkResult

internal open class ShareLinkResultDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ShareLinkResult
