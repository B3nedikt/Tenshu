package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TaskSpec
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.BackgroundTask
import org.tenshu.sync.GenericTaskSpec

internal class BackgroundTaskDto(
    fieldValues: List<Data?>
) : BackgroundTask, CompositeDto(fieldValues)

internal class GenericTaskSpecDto(
    fieldValues: List<Data?>
) : GenericTaskSpec, CompositeDto(fieldValues)

/** The fieldless polymorphic anchor — instantiated only via subtypes; registered for schema resolution. */
internal class TaskSpecDto(
    fieldValues: List<Data?>
) : TaskSpec<Data?>, CompositeDto(fieldValues)
