package org.tenshu.core.common.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.entity.GetStringDataId

class GetStringDataIdDto(
    fieldValues: List<Data?>
) : CompositeDto(fieldValues), GetStringDataId<Composite>
