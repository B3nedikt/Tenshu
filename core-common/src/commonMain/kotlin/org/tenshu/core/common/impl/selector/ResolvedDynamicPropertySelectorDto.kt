package org.tenshu.core.common.impl.selector

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.reference.DynamicPropertyTypeReference

/**
 * DTO for [Selector.DynamicPropertySelector.Resolved] — the explicit
 * `eager(...)`/`deferred(...)` override wire shape. Positional `listOf`: a null
 * [params] holds its slot so [deferred] maps to the third schema field.
 */
class ResolvedDynamicPropertySelectorDto<T : Data>(
    dynamicPropertyReference: DynamicPropertyTypeReference,
    params: TList<Data>?,
    deferred: TBoolean,
) : Selector.DynamicPropertySelector.Resolved<T>,
    CompositeDto(listOf(dynamicPropertyReference, params, deferred))
