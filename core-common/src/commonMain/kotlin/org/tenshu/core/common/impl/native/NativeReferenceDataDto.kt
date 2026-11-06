package org.tenshu.core.common.impl.native

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.native.NativeReferencedData

class NativeReferenceDataDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), NativeReferencedData