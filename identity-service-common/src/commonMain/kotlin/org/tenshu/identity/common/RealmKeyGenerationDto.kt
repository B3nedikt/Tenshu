package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.RealmKeyGeneration

/** Wire DTO for [RealmKeyGeneration] (GR-7 key ordering). */
class RealmKeyGenerationDto(
    fieldValues: List<Data?>,
) : RealmKeyGeneration, CompositeDto(fieldValues)
