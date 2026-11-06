package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.DeviceAlreadySetUpError

internal class DeviceAlreadySetUpErrorDto(
    fieldValues: List<Data?>
) : DeviceAlreadySetUpError, CompositeDto(fieldValues) {

    constructor() : this(fieldValues = emptyList())

    /** ⚠ Diagnostics only — a renderer maps the type to a catalogue entry, never this string. */
    override fun toString(): String = "Recovery refused: this device belongs to a different realm"
}
