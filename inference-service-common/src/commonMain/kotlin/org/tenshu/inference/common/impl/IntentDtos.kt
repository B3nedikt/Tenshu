package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.IntentCandidate
import org.tenshu.inference.IntentChoice

internal class IntentCandidateDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), IntentCandidate {

    override val schema: CompositeSchema get() = IntentCandidate.Schema
}

internal class IntentChoiceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), IntentChoice {

    override val schema: CompositeSchema get() = IntentChoice.Schema
}
