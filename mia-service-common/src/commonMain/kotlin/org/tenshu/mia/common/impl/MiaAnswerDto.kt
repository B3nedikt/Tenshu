package org.tenshu.mia.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.mia.MiaAnswer

internal class MiaAnswerDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MiaAnswer
