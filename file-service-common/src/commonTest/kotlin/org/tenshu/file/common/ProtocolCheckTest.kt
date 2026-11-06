package org.tenshu.file.common

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.dataFactory
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.reference.FieldReference
import kotlin.test.Test
import kotlin.test.assertNotNull

class ProtocolCheckTest {

    @Test
    fun checkReferenceSerialization() {
        val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
        createTestContext(registry)
        val serializer = BinarySerializer(registry)

        val ref = registry.getReference(FileService.ID)
        val bytes = serializer.serialize(ref)

        println("Reference bytes size: ${bytes.size}")
        println("Bytes: ${bytes.joinToString(",") { it.toUByte().toString(16).padStart(2, '0') }}")
    }

    @Test
    fun checkSelectSerialization() {
        val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
        val context = createTestContext(registry)
        val serializer = BinarySerializer(registry)

        val selector = Selector.FieldSelector.create<Data>(
            context,
            registry.getReference(File.Field.PATH) as FieldReference
        )

        // Ensure Select.Schema is in registry
        val selectSchema = registry.getSchema(Select.ID)
        assertNotNull(selectSchema, "Select schema should be registered")

        val selectOp = registry.createComposite<Operation>(
            Select.ID,
            listOf(listOf(selector).toTList(context))
        )

        val bytes = serializer.serialize(selectOp)
        println("Select bytes size: ${bytes.size}")
        println(
            "Select bytes: ${
                bytes.joinToString(",") {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
    }

    @Test
    fun checkEntitySerialization() {
        val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
        val context = createTestContext(registry)
        val serializer = BinarySerializer(registry)

        val path = "/test"
        val size = 12L
        val lastModified = 12345L

        val folderEntity = registry.createEntity<Folder>(
            type = Folder.ID,
            fields = mapOf<String, Data>(
                File.Field.PATH to context.dataFactory().createValue(FilePath.ID, path),
                Folder.Fields.FILES to TListDto<Data>(emptyList<Data>())
            ),
            // size + lastModifiedAt are value properties now (§3.3).
            relations = mapOf<String, Data>(
                "tenshu/file/category" to FileCategory.Audio,
                File.Size.id to context.dataFactory().createValue(File.Size.id, size),
                File.LastModifiedAt.id to context.dataFactory()
                    .createValue(File.LastModifiedAt.id, lastModified),
            )
        )

        val bytes = serializer.serialize(folderEntity)
        println("Entity bytes size: ${bytes.size}")
        println("Entity bytes: ${bytes.joinToString(",") { it.toString() }}")
    }

    @Test
    fun checkResultIds() {
        println("TResult.ID: ${TResult.ID}")
        println(
            "TResult.ID UUID (hex): ${
                idToUuid(TResult.ID).joinToString(",") {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
        println("TResult.ID UUID (bytes): ${idToUuid(TResult.ID).joinToString(",")}")

        println("TResult.Success.ID: ${TResult.Success.ID}")
        println(
            "TResult.Success.ID UUID (hex): ${
                idToUuid(TResult.Success.ID).joinToString(",") {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
        println("TResult.Success.ID UUID (bytes): ${idToUuid(TResult.Success.ID).joinToString(",")}")

        println("TList.ID: ${org.tenshu.core.collection.list.TList.ID}")
        println(
            "TList.ID UUID (bytes): ${
                idToUuid(org.tenshu.core.collection.list.TList.ID).joinToString(
                    ","
                )
            }"
        )

        println("Entity.ID: ${org.tenshu.core.entity.Entity.ID}")
        println("Entity.ID UUID (bytes): ${idToUuid(org.tenshu.core.entity.Entity.ID).joinToString(",")}")

        println("Reference.ID: ${org.tenshu.core.reference.Reference.ID}")
        println(
            "Reference.ID UUID (bytes): ${
                idToUuid(org.tenshu.core.reference.Reference.ID).joinToString(
                    ","
                )
            }"
        )

        println("Failure.ID: ${TResult.Failure.ID}")
        println("Failure.ID UUID (bytes): ${idToUuid(TResult.Failure.ID).joinToString(",")}")

        println("GenericError.ID: tenshu/core/error/generic")
        println(
            "GenericError.ID UUID (bytes): ${
                idToUuid("tenshu/core/error/generic").joinToString(
                    ","
                )
            }"
        )
    }
}
