package org.tenshu.file.proxy


import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.common.module.dynamicPropertyRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.entity.get
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.getPreview
import org.tenshu.core.file.path
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.dynamicPropertyRef
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.tenshu.core.schema.dynamicPropertyRelation as schemaDynamicPropertyRelation

/**
 * `select` projection. Post field->property migration (§3.3): `path` is the only
 * `File` field; `size` / `lastModifiedAt` are value properties projected via
 * `PropertySelector`, alongside the existing composite (`FileCategory`) and
 * dynamic (`Data`/`Preview`) property selectors.
 */
@OptIn(ExperimentalTime::class)
class SelectOperationTest {

    @Test
    fun expectedFieldAndPropertiesGetSelected() {

        val testProxy = createFileProxy()
        val context = testProxy.tContext

        val lastModified = Clock.System.now().toEpochMilliseconds()
        val files = listOf(
            Entity.create<File>(
                context = context,
                composite = File.create(context, "/test"),
            ) {
                set(File.Size, 0L)
                set(File.LastModifiedAt, lastModified)
            }
        )

        // Value properties resolve from the stored entity, so persist it first.
        testProxy.database.saveData(files[0])

        val caller = TListDto(files)

        val selectors = listOf(
            Selector.FieldSelector.create<File>(context, fieldReference = fieldRef(File.Field.PATH)),
            Selector.PropertySelector.create<File>(
                context, propertyReference = propertyRef(File.Size.id)
            ),
            Selector.PropertySelector.create<File>(
                context, propertyReference = propertyRef(File.LastModifiedAt.id)
            ),
        )

        val result = testProxy.executionContext.execute<TList<Entity<File>>>(
            operationId = Select.ID,
            caller = caller,
            params = mapOf(
                Select.Params.SELECTORS to TListDto(selectors)
            )
        )

        val firstItem = result.toList()[0]

        assertEquals(expected = "/test", actual = firstItem.path.get())
        assertEquals(expected = 0L, actual = firstItem.get(File.Size)?.get())
        assertEquals(expected = lastModified, actual = firstItem.get(File.LastModifiedAt)?.get())
    }

    @Test
    fun propertyReferenceSelectsTheProperty() {

        val testProxy = createFileProxy()
        val context = testProxy.tContext

        val lastModified = Clock.System.now().toEpochMilliseconds()

        // ⚠ `.jpg`, and no seeded category: since PH-1 (`photos-app.md`) `File.Category` is
        // DERIVED from the name by `FileCategoryOf`, so what the projection must produce is the
        // answer the extension implies — not a value a caller planted in the row.
        val testFile = Entity.create<File>(
            context = context,
            composite = File.create(context = context, path = "/test.jpg"),
        ) {
            set(File.Size, 0L)
            set(File.LastModifiedAt, lastModified)
        }

        testProxy.database.saveData(testFile)

        val caller = TListDto(listOf(testFile))

        val selectors = listOf(
            Selector.FieldSelector.create<File>(context, fieldReference = fieldRef(File.Field.PATH)),
            Selector.PropertySelector.create<File>(
                context, propertyReference = propertyRef(File.Size.id)
            ),
            Selector.PropertySelector.create<File>(
                context, propertyReference = propertyRef(File.LastModifiedAt.id)
            ),
            // A DYNAMIC selector: the category is chain-computed per row now, like `File.Name`.
            Selector.DynamicPropertySelector.create<File>(
                context, dynamicPropertyReference = dynamicPropertyRef(FileCategory.Schema.id.get())
            ),
        )

        val result = testProxy.executionContext.execute<TList<Entity<File>>>(
            operationId = Select.ID,
            caller = caller,
            params = mapOf(
                Select.Params.SELECTORS to selectors.toTList(context)
            )
        )

        val firstItem = result.toList()[0]

        assertEquals(expected = File.ID, actual = firstItem.type.get())
        assertEquals(
            expected = "/test.jpg",
            actual = (firstItem.fields[FieldReferenceDto(File.Field.PATH)] as FilePath).get()
        )
        assertEquals(
            expected = 0L,
            actual = (firstItem.properties[PropertyTypeReferenceDto(File.Size.id)] as TInteger).get()
        )
        assertEquals(
            expected = lastModified,
            actual = (firstItem.properties[PropertyTypeReferenceDto(File.LastModifiedAt.id)] as TInteger).get()
        )
        assertEquals(
            expected = FileCategory.Image,
            actual = firstItem.properties[PropertyTypeReferenceDto(FileCategory.Schema.id.get())]
        )
    }

    @Test
    fun dynamicPropertyReferenceSelectsTheProperty() {

        val testProxy = createFileProxy()
        val context = testProxy.tContext

        val testFile = File.create(context = context, path = "/test")

        testProxy.database.saveData(testFile)

        val caller = TListDto(listOf(testFile))

        val fileBytes = byteArrayOf(1, 2, 3)

        testProxy.fileDatabase.writeByteArray(
            filePath = "/test",
            bytes = TByteArrayDto(fileBytes)
        )

        val selectors = listOf(
            Selector.FieldSelector.create<File>(context, fieldReference = fieldRef(File.Field.PATH)),
            Selector.DynamicPropertySelector.create<File>(
                context = context,
                dynamicPropertyReference = dynamicPropertyRef(File.Data.id),
            ),
        )

        val result = testProxy.executionContext.execute<TList<Entity<File>>>(
            operationId = Select.ID,
            caller = caller,
            params = mapOf(
                Select.Params.SELECTORS to TListDto(selectors)
            )
        )

        val firstItem = result.toList()[0]

        assertEquals(expected = File.ID, actual = firstItem.type.get())
        assertEquals(
            expected = "/test",
            actual = (firstItem.fields[FieldReferenceDto(File.Field.PATH)] as FilePath).get()
        )
        assertEquals(
            expected = TByteArrayDto(fileBytes),
            actual = firstItem.properties[DynamicPropertyTypeReferenceDto(File.Data.id)]
        )
    }

    @Test
    fun emptyDynamicPropertyReferenceSelectsNoProperty() {

        val testProxy = createFileProxy()
        val context = testProxy.tContext

        val testFile = File.create(context = context, path = "/test")

        testProxy.database.saveData(testFile)

        val caller = TListDto(listOf(testFile))

        val selectors = listOf(
            Selector.FieldSelector.create<File>(context, fieldReference = fieldRef(File.Field.PATH)),
            Selector.PropertySelector.create<File>(
                context, propertyReference = propertyRef(File.Data.id)
            ),
        )

        val result = testProxy.executionContext.execute<TList<Entity<File>>>(
            operationId = Select.ID,
            caller = caller,
            params = mapOf(
                Select.Params.SELECTORS to TListDto(selectors)
            )
        )

        val firstItem = result.toList()[0]

        assertEquals(expected = File.ID, actual = firstItem.type.get())
        assertEquals(
            expected = "/test",
            actual = (firstItem.fields[FieldReferenceDto(File.Field.PATH)] as FilePath).get()
        )
        assertEquals(
            expected = null,
            actual = firstItem.properties[DynamicPropertyTypeReferenceDto(File.Data.id)]
        )
        assertEquals(expected = 0, actual = firstItem.properties.size)
    }

    @Test
    fun dynamicPropertyWithParametersSelectsTheProperty() {

        val testProxy = createFileProxy()
        val context = testProxy.tContext

        val testBytes = TByteArrayDto(byteArrayOf(1, 2))
        testProxy.additionalImplementations[GetPreview.ID] = object : SyncOperationImplementation {

            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                @Suppress("UNCHECKED_CAST")
                return testBytes as T
            }
        }

        val testFile = File.create(context = context, path = "/test")

        testProxy.database.saveData(testFile)

        val caller = TListDto(listOf(testFile))

        val dynamicPropertyId = "test/dynamicProperty"
        val config = tModule {
            dynamicPropertyRelation(
                fromId = File.ID,
                schema = schemaDynamicPropertyRelation(
                    id = dynamicPropertyId,
                    params = listOf(TInteger.Schema, TInteger.Schema),
                    callstack = ChainTemplate<File, TByteArray> { params ->
                        val width = params[0] as Long
                        val height = params[1] as Long

                        getPreview(width = width.toInt(), height = height.toInt())
                    }
                )
            )
        }
        testProxy.schemaRegistry.registerConfig(config)

        val selectors = listOf(
            Selector.FieldSelector.create<File>(context, fieldReference = fieldRef(File.Field.PATH)),
            Selector.DynamicPropertySelector.create<File>(
                context = context,
                dynamicPropertyReference = dynamicPropertyRef(dynamicPropertyId),
                params = TListDto(listOf(100.toTInteger(context), 100.toTInteger(context)))
            ),
        )

        val result = testProxy.executionContext.execute<TList<Entity<File>>>(
            operationId = Select.ID,
            caller = caller,
            params = mapOf(
                Select.Params.SELECTORS to TListDto(selectors)
            )
        )

        val firstItem = result.toList()[0]

        assertEquals(expected = File.ID, actual = firstItem.type.get())
        assertEquals(
            expected = "/test",
            actual = (firstItem.fields[FieldReferenceDto(File.Field.PATH)] as FilePath).get()
        )
        assertEquals(
            expected = testBytes,
            actual = firstItem.properties[DynamicPropertyTypeReferenceDto(dynamicPropertyId)]
        )
        assertEquals(expected = 1, actual = firstItem.properties.size)
    }
}
