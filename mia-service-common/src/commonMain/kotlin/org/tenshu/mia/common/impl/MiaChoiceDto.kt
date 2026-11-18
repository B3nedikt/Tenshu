package org.tenshu.mia.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.mia.MiaChoice

internal class MiaChoiceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MiaChoice
