package org.tenshu.file.common.impl.file

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.CapturedPhoto

internal class CapturedPhotoDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CapturedPhoto
