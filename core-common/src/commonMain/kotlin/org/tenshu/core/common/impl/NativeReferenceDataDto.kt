package org.tenshu.impl.native

import org.tenshu.core.Data
import org.tenshu.core.native.NativeReferencedData

class NativeReferenceDataDto(
    fieldValues: List<Data?>,
) : org.tenshu.core.common.impl.CompositeDto(fieldValues), NativeReferencedData