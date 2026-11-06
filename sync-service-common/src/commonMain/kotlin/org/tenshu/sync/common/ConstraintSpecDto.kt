package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.ConstraintSpec

internal class ConstraintSpecDto(
    fieldValues: List<Data?>
) : ConstraintSpec, CompositeDto(fieldValues)
