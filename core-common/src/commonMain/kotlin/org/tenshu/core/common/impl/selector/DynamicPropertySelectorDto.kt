package org.tenshu.core.common.impl.selector

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.reference.DynamicPropertyTypeReference

class DynamicPropertySelectorDto<T : Data>(
    dynamicPropertyReference: DynamicPropertyTypeReference,
    params: TList<Data>?
) : Selector.DynamicPropertySelector<T>, CompositeDto(listOf(dynamicPropertyReference, params))