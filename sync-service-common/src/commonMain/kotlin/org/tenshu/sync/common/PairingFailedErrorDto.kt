package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure

internal class PairingFailedErrorDto(
    private val fieldValues: List<Data?>
) : PairingFailedError, CompositeDto(fieldValues) {

    constructor(reason: PairingFailure) : this(fieldValues = listOf(reason))

    /**
     * ⚠ Diagnostics only — the reason's schema id, not copy. A renderer maps
     * [PairingFailedError.reason] to a catalogue entry; nothing user-facing may read this
     * (`core-error-copy-catalogue.md` §5.3).
     */
    override fun toString(): String =
        "Pairing failed: ${(fieldValues.firstOrNull() as? PairingFailure)?.schema?.id}"
}
