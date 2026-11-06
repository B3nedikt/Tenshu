package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.SignedHlcTip

internal class SignedHlcTipDto(
    fieldValues: List<Data?>
) : SignedHlcTip, CompositeDto(fieldValues)
