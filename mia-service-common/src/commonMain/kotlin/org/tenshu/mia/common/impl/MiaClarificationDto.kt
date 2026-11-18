package org.tenshu.mia.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.mia.MiaClarification

internal class MiaClarificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MiaClarification
