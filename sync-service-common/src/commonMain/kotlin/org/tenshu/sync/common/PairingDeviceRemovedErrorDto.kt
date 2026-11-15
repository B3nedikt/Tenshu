package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingDeviceRemovedError

internal class PairingDeviceRemovedErrorDto(
    fieldValues: List<Data?>
) : PairingDeviceRemovedError, CompositeDto(fieldValues) {

    constructor() : this(fieldValues = emptyList())

    /** ⚠ Diagnostics only — a renderer maps the type to copy, never this string. */
    override fun toString(): String = "Pairing refused: the device being added was removed from this realm"
}
