package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.RealmGrantor

/** Wire DTO for [RealmGrantor] (GR-7 re-key routing). */
class RealmGrantorDto(
    fieldValues: List<Data?>,
) : RealmGrantor, CompositeDto(fieldValues)
