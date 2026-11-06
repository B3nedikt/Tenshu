package org.tenshu.file.common.impl.share

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.share.Share

/**
 * DTO for [Share] — mirrors `FileDto` / `FileModificationDto`. Only the
 * `fieldValues` ctor is required; the secondary helper ctor mirrors
 * `Share.create(...)` for ergonomic test/in-memory construction.
 */
internal open class ShareDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Share
