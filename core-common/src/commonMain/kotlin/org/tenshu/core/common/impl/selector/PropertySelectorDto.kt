package org.tenshu.core.common.impl.selector

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.reference.PropertyTypeReference

class PropertySelectorDto<T : Data>(
    propertyReference: PropertyTypeReference,
    nested: TList<Selector<*>>? = null,
) : Selector.PropertySelector<T>, CompositeDto(listOfNotNull(propertyReference, nested))