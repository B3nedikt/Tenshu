package org.tenshu.calendar.common.impl

import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto

/**
 * ⚠ **DTO only, no `Ro`** — the `CapturedPhoto` precedent. A chain never *projects* this type: it
 * comes back whole from `queryOccurrences` and nothing selects fields off it, so the RO half that
 * every stored composite needs has no call site here.
 */
internal class ResolvedEventOccurrenceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ResolvedEventOccurrence
