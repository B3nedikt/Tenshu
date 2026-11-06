package org.tenshu.core.common.impl

import org.tenshu.core.time.UnixInstant

class UnixInstantDto(
    value: Any
) : UnixInstant, TIntegerDto(value) {

    override fun toEpochMilliseconds(): Long = get()
}