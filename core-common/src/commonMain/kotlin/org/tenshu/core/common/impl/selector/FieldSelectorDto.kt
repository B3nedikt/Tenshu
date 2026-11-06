package org.tenshu.core.common.impl.selector

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.reference.FieldReference

class FieldSelectorDto<T : Data>(
    fieldReference: FieldReference
) : Selector.FieldSelector<T>, CompositeDto(listOf(fieldReference))
