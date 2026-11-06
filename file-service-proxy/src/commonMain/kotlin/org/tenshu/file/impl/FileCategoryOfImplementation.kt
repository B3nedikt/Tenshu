package org.tenshu.file.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategoryOf
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.path
import org.tenshu.core.entity.Entity

/**
 * [FileCategoryOf] — the file's category, from the last segment of its path
 * (`photos-app.md` PH-1).
 *
 * The one place the extension→category `when` runs from now on. It was written three times before
 * this op existed (Drive's row glyph, both photo ops, the preview resolvers), each copy able to
 * drift from the others.
 *
 * ⚠ **Synchronous and allocation-cheap on purpose.** This runs once per row of a realm-wide scan —
 * a 20 000-photo feed page evaluates it 20 000 times — so it may not touch the database or the
 * filesystem. It does not: the path is already in the row the engine handed it.
 */
internal class FileCategoryOfImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // ⚠ `caller` FIRST, exactly as `GetStoredThumbnailImplementation` does. Inside a
        // dynamic-property template the FILE *param* is the chain's recording placeholder — asking
        // it for a path throws "Not a data object" — while `caller` is the row the engine is
        // resolving. The param remains the fallback for a direct `file.categoryOf()` call.
        val file = caller as? Composite
            ?: params?.get(FileCategoryOf.Params.FILE) as? Composite
            ?: error("Parameter ${FileCategoryOf.Params.FILE} is required")

        val path = when (file) {
            is Entity<*> -> {
                @Suppress("UNCHECKED_CAST")
                (file as Entity<File>).path.get()
            }

            else -> (file as File).path.get()
        }

        return fileCategoryForName(path.substringAfterLast('/')) as T
    }
}
