package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.entity.ResolveEntityDataId

class ResolveEntityDataIdDto(
    fieldValues: List<Data?>
) : CompositeDto(fieldValues), ResolveEntityDataId
