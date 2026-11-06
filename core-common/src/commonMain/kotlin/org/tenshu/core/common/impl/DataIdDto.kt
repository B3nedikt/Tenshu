package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId

abstract class DataIdDto(
    fieldValues: List<Data?>
) : CompositeDto(fieldValues), DataId

