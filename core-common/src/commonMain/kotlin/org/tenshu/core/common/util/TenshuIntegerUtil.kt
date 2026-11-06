package org.tenshu.core.common.util

import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.number.TInteger

fun Long.toInteger(): TInteger {
    return TIntegerDto(this)
}