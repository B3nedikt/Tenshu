package org.tenshu.mia.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.mia.MiaAction

internal class MiaActionDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MiaAction
