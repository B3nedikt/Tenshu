package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingApproverRevokedError

internal class PairingApproverRevokedErrorDto(
    fieldValues: List<Data?>
) : PairingApproverRevokedError, CompositeDto(fieldValues) {

    constructor() : this(fieldValues = emptyList())

    /** ⚠ Diagnostics only — a renderer maps the type to copy, never this string. */
    override fun toString(): String = "Pairing refused: this device's admission capability was revoked"
}
