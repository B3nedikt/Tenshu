package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingOfferApprovalPrompt

internal class PairingOfferApprovalPromptDto(
    fieldValues: List<Data?>
) : PairingOfferApprovalPrompt, CompositeDto(fieldValues)
