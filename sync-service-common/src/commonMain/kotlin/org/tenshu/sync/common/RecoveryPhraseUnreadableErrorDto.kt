package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.RecoveryPhraseUnreadableError

internal class RecoveryPhraseUnreadableErrorDto(
    fieldValues: List<Data?>
) : RecoveryPhraseUnreadableError, CompositeDto(fieldValues) {

    constructor() : this(fieldValues = emptyList())

    /** ⚠ Diagnostics only — a renderer maps the type to a catalogue entry, never this string. */
    override fun toString(): String = "Stored recovery phrase cannot be decoded"
}
