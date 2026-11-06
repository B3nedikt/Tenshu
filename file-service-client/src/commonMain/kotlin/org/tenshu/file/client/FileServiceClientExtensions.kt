package org.tenshu.file.client

import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.filter
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.equalTo
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService

val TenshuClient.fileService: FileService
    get() = getService(FileService.ID)

/**
 * The per-file history: every [FileModification] whose `fileId` matches
 * [filePath], as a lazy chain over the realm-wide [FileService.modifications]
 * log. Sort (newest-first) and project at the call site — e.g.
 * `modificationsForFile(path).sortByDescending { timestamp }.select { … }.flow()`.
 *
 * The parameter is named `filePath` (not `fileId`) so the predicate's
 * unqualified `fileId` resolves to [FileModification.fileId], not the argument.
 */
fun FileService.modificationsForFile(filePath: String): TList<FileModification> =
    modifications.filter<FileModification> { fileId equalTo filePath }
