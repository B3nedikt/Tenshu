package org.tenshu.core.common.schema

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto

/*
 * Wire DTOs for the frozen schema-versioning composites (§6.2). Internal —
 * consumers go through each interface's `Companion.create`.
 */

internal class ServiceVersionDto(
    fieldValues: List<Data?>
) : ServiceVersion, CompositeDto(fieldValues)

internal class VersionVectorDto(
    fieldValues: List<Data?>
) : VersionVector, CompositeDto(fieldValues)

internal class ServiceVersionMismatchDto(
    fieldValues: List<Data?>
) : ServiceVersionMismatch, CompositeDto(fieldValues)

internal class SessionAdmissionDto(
    fieldValues: List<Data?>
) : SessionAdmission, CompositeDto(fieldValues)
