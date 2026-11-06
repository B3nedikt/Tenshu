package org.tenshu.file.proxy.proxy

import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.evaluateCallstack
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.path
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.file.impl.CreateFolderImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * File-schema semantics of the by-key search short-circuit, driven through
 * [evaluateCallstack] against the *raw* root-files [ArrayDatabaseReference]
 * (the shape `navigateTo(parent)`/`getPreview` produce proxy-side). The
 * dispatch-level proof (point read vs scan) lives in `:core-common`'s
 * `SearchByKeyFastPathTest`; here the assertions are result-parity:
 *
 * - a `search<Folder>` by path resolves through the **identity root** —
 *   Folder rows are stored under `StringDataId(File.ID, path)`;
 * - a *nested* folder (member of a subfolder's files list, not of the root
 *   array) is still found — membership miss must fall back to the scan's
 *   recursive descent, never settle Empty.
 */
class SearchByKeyIntegrationTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setUp() {
        proxy = createFileProxy()
    }

    @Test
    fun folderByPath_resolvesThroughIdentityRoot() {
        CreateFolderImplementation().createFolder(proxy.executionContext, "/docs")

        val result = evaluateCallstack(
            executionContext = proxy.executionContext,
            contextData = rootFilesRef(),
            callstack = listOf(searchByPath("/docs")),
            allowDeferring = false,
        )

        val found = assertIs<Maybe.Some<*>>(result)
        assertEquals("/docs", (found.value as Folder).path.get())
    }

    @Test
    fun nestedFolder_notInRootArray_isStillFoundViaScanDescent() {
        CreateFolderImplementation().createFolder(proxy.executionContext, "/docs")
        CreateFolderImplementation().createFolder(proxy.executionContext, "/docs/inner")

        // "/docs/inner" is a member of "/docs"'s files array, NOT of the root
        // array — the by-key membership probe misses, and the result must
        // come from the scan's nested descent (a fast path that returned
        // Empty on miss would break navigateTo for any nested path).
        val result = evaluateCallstack(
            executionContext = proxy.executionContext,
            contextData = rootFilesRef(),
            callstack = listOf(searchByPath("/docs/inner")),
            allowDeferring = false,
        )

        val found = assertIs<Maybe.Some<*>>(result)
        assertEquals("/docs/inner", (found.value as Folder).path.get())
    }

    @Test
    fun absentPath_isEmpty() {
        CreateFolderImplementation().createFolder(proxy.executionContext, "/docs")

        val result = evaluateCallstack(
            executionContext = proxy.executionContext,
            contextData = rootFilesRef(),
            callstack = listOf(searchByPath("/nope")),
            allowDeferring = false,
        )

        assertIs<Maybe.Empty<*>>(result)
    }

    // ─── helpers ───

    private fun rootFilesRef(): ArrayDatabaseReference {
        @Suppress("UNCHECKED_CAST")
        val rootFolder = proxy.executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT)
        ) as Entity<Folder>

        return ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = proxy.executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false,
            elementSchemaId = File.ID
        )
    }

    private fun searchByPath(path: String) = OperationDto(
        params = mapOf(
            Search.Params.PREDICATE to listOf(
                createFileTestRegistry().getReference(File.Field.PATH),
                OperationDto(
                    params = mapOf(EqualTo.Params.OTHER to path.toTString()),
                    schema = EqualTo.Schema
                )
            ).toTList()
        ),
        schema = Search.Schema
    )
}
