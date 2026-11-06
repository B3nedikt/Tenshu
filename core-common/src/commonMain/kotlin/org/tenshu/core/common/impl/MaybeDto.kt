package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Maybe

class MaybeSomeDto<T : Data>(
    private val fieldValues: List<Data?>
) : Maybe.Some<T>, CompositeDto(fieldValues)

class MaybeEmptyDto<T : Data>(
    private val fieldValues: List<Data?>
) : Maybe.Empty<T>, CompositeDto(fieldValues)
