package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.RecoveryPhraseRequiredError

internal class RecoveryPhraseRequiredErrorDto(
    fieldValues: List<Data?>
) : RecoveryPhraseRequiredError, CompositeDto(fieldValues) {

    constructor() : this(fieldValues = emptyList())

    /** ⚠ Diagnostics only — a renderer maps the type to a catalogue entry, never this string. */
    override fun toString(): String = "Removing another device needs the recovery phrase"
}
