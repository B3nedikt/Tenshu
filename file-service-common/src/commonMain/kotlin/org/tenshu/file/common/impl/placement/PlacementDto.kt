package org.tenshu.file.common.impl.placement

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.placement.Placement

internal class PlacementDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Placement
