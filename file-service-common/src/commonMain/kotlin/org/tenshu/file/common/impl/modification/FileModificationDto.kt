package org.tenshu.file.common.impl.modification

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.FileModification
import org.tenshu.core.schema.CompositeSchema

/**
 * DTO for the abstract [FileModification] parent. Mirrors `NotificationDto`:
 * registering the parent with its `interfaceClass` is what maps
 * `FileModification::class` → its type id, which the chain DSL needs to resolve
 * `modifications.filter<FileModification> { … }` (the per-file history accessor).
 * Concrete rows always deserialise via their own subtype DTOs; this parent
 * factory is only the class-mapping anchor.
 */
internal open class FileModificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), FileModification {

    override val schema: CompositeSchema
        get() = FileModification.Schema
}
