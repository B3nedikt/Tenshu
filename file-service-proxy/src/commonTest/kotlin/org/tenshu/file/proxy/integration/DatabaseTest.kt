package org.tenshu.file.proxy.integration


import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.NoOpDatabaseLock
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.Category
import org.tenshu.core.file.File
import org.tenshu.core.file.Size
import org.tenshu.core.number.toTInteger
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.path
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DatabaseTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setup() {
        tContext = createTestContext(createFileTestRegistry())
    }

    @Test
    fun saveAndGetData() {
        // Create SUT
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)

        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // Create test data
        val folderPath = "/test"
        val folderTestSize = 0L
        val folderTestLastModifiedTimeStamp = Clock.System.now().toEpochMilliseconds()

        val testPath1 = "/test/test1"
        val testSize1 = 0L
        val testLastModifiedTimeStamp1 = Clock.System.now().toEpochMilliseconds()

        val testPath2 = "/test/test2"
        val testSize2 = 52L
        val testLastModifiedTimeStamp2 = Clock.System.now().toEpochMilliseconds()

        val files = listOf(
            File.create(
                context = context,
                path = testPath1,
            ),
            File.create(
                context = context,
                path = testPath2,
            )
        )

        val folder = Folder.create(
            context = context,
            path = folderPath,
            files = files
        )

        database.saveData(folder)

        val dataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )

        val result = database.getData(dataId) as Folder

        // size/lastModifiedAt are entity-only value properties (§3.3); this path
        // reads composites, so the test asserts identity (path) + membership.
        // Property round-trip is covered by ValuePropertyRoundTripTest +
        // SelectOperationTest.
        assertEquals(result.path.get(), folderPath)

        val filesField = result.getField<Data>(Folder.Fields.FILES)
        val filesList = database.getArrayData(filesField as ArrayDatabaseReference).toList()
        assertEquals(filesList.size, 2)

        val file1 = filesList[0] as File
        assertEquals(file1.path.get(), testPath1)

        val file2 = filesList[1] as File
        assertEquals(file2.path.get(), testPath2)
    }

    /**
     * `getArrayDataWindow` deserialises only the `[offset, offset + limit)`
     * members, in insertion order, with out-of-range windows clamped — the
     * real-store override behind the `Database` interface default.
     */
    @Test
    fun getArrayDataWindow_slicesInInsertionOrder() {
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)
        val traitResolver = TraitResolverImpl { context }
        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        val paths = (0 until 6).map { "/test/file$it" }
        val folder = Folder.create(
            context = context,
            path = "/test",
            files = paths.map { File.create(context = context, path = it) }
        )
        database.saveData(folder)

        val stored = database.getData(
            DataId.StringDataId.create(context = context, type = File.ID, keyValue = "/test")
        ) as Folder
        val filesRef = stored.getField<Data>(Folder.Fields.FILES) as ArrayDatabaseReference

        fun window(offset: Int, limit: Int): List<String> =
            database.getArrayDataWindow(filesRef, offset = offset, limit = limit)!!
                .toList()
                .map { (it as File).path.get() }

        assertEquals(paths.subList(2, 5), window(offset = 2, limit = 3))
        assertEquals(paths.subList(0, 2), window(offset = 0, limit = 2))
        // Partial last window clamps; offset past the end is empty.
        assertEquals(paths.subList(4, 6), window(offset = 4, limit = 10))
        assertEquals(emptyList(), window(offset = 9, limit = 3))
        // Full-array window round-trips everything.
        assertEquals(paths, window(offset = 0, limit = Int.MAX_VALUE))

        // Missing array → empty sequence, mirroring getArrayData.
        val missingRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context, TList.ID, "test/missing-array"),
            isEntity = false,
        )
        assertEquals(
            emptyList(),
            database.getArrayDataWindow(missingRef, offset = 0, limit = 5)!!.toList()
        )
    }

    @Test
    fun listenerGetsTriggered() {
        // Create SUT
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)

        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // Create test data
        val folderPath = "/test"
        val folderTestSize = 0L
        val folderTestLastModifiedTimeStamp = Clock.System.now().toEpochMilliseconds()

        val testPath1 = "/test/test1"
        val testSize1 = 0L
        val testLastModifiedTimeStamp1 = Clock.System.now().toEpochMilliseconds()

        val file1 = File.create(
            context = context,
            path = testPath1,
        )

        val testPath2 = "/test/test2"
        val testSize2 = 52L
        val testLastModifiedTimeStamp2 = Clock.System.now().toEpochMilliseconds()

        val file2 = File.create(
            context = context,
            path = testPath2,
        )

        val folder = Folder.create(
            context = context,
            path = folderPath,
            files = emptyList()
        )

        database.saveData(folder)

        val updates = mutableListOf<File>()

        val folderDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )

        database.setArrayListener(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = folderDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            onAdded = { newFile, _ ->
                updates.add(newFile as File)
            },
            onRemoved = { _, _ -> }
        )

        // Get folder add two files and save folder again
        addFileToDb(context, database, folderPath, file1)
        addFileToDb(context, database, folderPath, file2)

        // Updates should contain the new files
        assertEquals(2, updates.size)

        val firstUpdate = updates[0]
        assertEquals(testPath1, firstUpdate.path.get())

        val secondUpdate = updates[1]
        assertEquals(testPath2, secondUpdate.path.get())
    }

    @Test
    fun removalListenerGetsTriggered() {
        // Create SUT
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)

        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // Create test data
        val folderPath = "/test"
        val testPath1 = "/test/test1"
        val file1 =
            File.create(context = context, path = testPath1)

        val folder = Folder.create(
            context = context,
            path = folderPath,
            files = listOf(file1)
        )

        database.saveData(folder)

        val removedIds = mutableListOf<DataId>()

        val folderDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )

        database.setArrayListener(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = folderDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            onAdded = { _, _ -> },
            onRemoved = { removedId, _ ->
                removedIds.add(removedId)
            }
        )

        // Remove the file via the proper `removeFromArray` path.
        //
        // The previous "save folder with empty list" approach relied on
        // `DbEntryMapper.writeValuesToDb`'s `TListDto<*>`
        // branch overwriting the array DataId with the empty list. That
        // serializer-level overwrite was removed because it races with
        // `Database.addToArray` (used by `ParentFilesRelationMaintainer`):
        // a stale `Folder` SyncBlock processed via `FolderMerger` could
        // wholesale erase folders/files the maintainer had just added.
        // Removals now go through the dedicated `removeFromArray`/`deleteData`
        // primitives so the array can only grow on field writes.
        val testPathDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = testPath1
        )
        database.removeFromArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = folderDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            dataId = testPathDataId
        )

        assertEquals(1, removedIds.size)
        val removedId = removedIds[0] as DataId.StringDataId
        assertEquals(testPath1, removedId.keyValue.get())
    }

    @Test
    fun entitySaveAndGetData() {
        // Create SUT
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)

        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // Create test data
        val folderPath = "/test"
        val folderTestSize = 0L
        val folderTestLastModifiedTimeStamp = Clock.System.now().toEpochMilliseconds()

        val folder = Entity.create<Folder>(
            context = context,
            composite = Folder.create(
                context = context,
                path = folderPath,
                files = emptyList()
            ),
            // ⚠ Was `FileCategory.Image` until PH-1 (`photos-app.md`) made the category a
            // derived dynamic property. This test is about the mapper — that a property cell
            // survives save → load — so it needs a property that is still *stored*; `File.Size`
            // is one, and the round-trip it pins is the same one.
            properties = mapOf(
                File.Size.id to folderTestSize.toTInteger(context)
            )
        )

        database.saveData(folder)

        val dataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )

        @Suppress("UNCHECKED_CAST")
        val result = database.getData(dataId, asEntity = true) as Entity<Folder>

        assertEquals(result.path.get(), folderPath)
        assertEquals(
            folderTestSize,
            result.get(File.Size)?.get()
        )
    }

    /**
     * Only-grow regression for the `TListDto<*>` branch of
     * [DbEntryMapper.writeValuesToDb]:
     *
     * If a [Folder] is saved with `files=[a, b]` and then re-saved with
     * `files=[a]`, the array DataId must still hold *both* refs. The
     * production-shape save path (via [DatabaseImpl.saveData] → `getEntry`
     * wired) unions the existing refs with the new ones.
     *
     * Without this invariant a stale `Folder` SyncBlock processed via
     * `FolderMerger` → `saveData(merged)` races with concurrent
     * `addToArray` calls from `ParentFilesRelationMaintainer` and can
     * erase folders/files that the maintainer just added.
     */
    @Test
    fun saveData_doesNotShrinkParameterizedList() {
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)
        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        val folderPath = "/test"
        val fileA = File.create(context, "/test/a")
        val fileB = File.create(context, "/test/b")

        // First save: files = [a, b]
        database.saveData(
            Folder.create(
                context = context,
                path = folderPath,
                files = listOf(fileA, fileB)
            )
        )

        // Second save: files = [a] only — must not erase b.
        database.saveData(
            Folder.create(
                context = context,
                path = folderPath,
                files = listOf(fileA)
            )
        )

        val folderDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )
        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = folderDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val paths = database.getArrayData(arrayRef).toList()
            .map { (it as File).path.get() }
            .toSet()

        assertEquals(
            setOf("/test/a", "/test/b"),
            paths,
            "Re-saving a Folder with a shorter files list must preserve already-stored refs"
        )
    }

    /**
     * Pins the `ArrayDatabaseReference` branch of
     * [DbEntryMapper.writeValuesToDb]: a composite
     * loaded from the database has its list fields rehydrated as
     * `ArrayDatabaseReference`s. The serializer must keep the reference
     * rather than refuse to handle it.
     *
     * Production trigger: `SyncProcessor.resolveConflictAndSave` runs
     * `FolderMerger.merge(existing, incoming)` and saves the merged
     * `Folder`. The merger's `files` list contains entries resolved from
     * the local DB — for a nested `Folder` child, the loaded composite's
     * own `FILES` is an `ArrayDatabaseReference`. Without the dedicated
     * branch, `writeValuesToDb` hits the `else` arm and throws
     * `IllegalStateException("Unsupported data type for DB
     * serialization: class ArrayDatabaseReference")`.
     */
    @Test
    fun saveData_acceptsLoadedCompositeWithArrayDatabaseReferenceFields() {
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)
        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // 1. Save a folder F1 with a child file — populates F1's FILES
        //    array entry with the child's ref.
        val folderPath = "/F1"
        val childFile = File.create(context, "$folderPath/a")
        database.saveData(
            Folder.create(
                context = context,
                path = folderPath,
                files = listOf(childFile)
            )
        )

        // 2. Load F1 back from the DB. The composite's FILES field is now
        //    `ArrayDatabaseReference` (not `TListDto`) — this is the shape
        //    `database.getData(StringDataId)` always returns for list
        //    fields, and the exact shape `FolderMerger` hands to the
        //    serializer when it puts a loaded child into the merged
        //    parent's files list.
        val folderDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )
        val loadedFolder = database.getData(folderDataId) as Folder

        // 3. Re-save the loaded folder. Pre-fix: throws on the FILES
        //    field. Post-fix: the `ArrayDatabaseReference` branch records
        //    the existing array's hash without rewriting the array entry.
        database.saveData(loadedFolder)

        // 4. F1's child array must still hold the child file — the save
        //    must not have erased its members.
        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = folderDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val childPaths = database.getArrayData(arrayRef)?.toList()
            ?.map { (it as File).path.get() }
            ?.toSet()
            ?: emptySet()
        assertEquals(
            setOf("$folderPath/a"),
            childPaths,
            "Re-saving a loaded folder must preserve its FILES array members"
        )
    }

    /**
     * The dual of [saveData_doesNotShrinkParameterizedList]: two disjoint
     * saves with non-overlapping file sets must union to the full set.
     * This is exactly the shape of `host: createFolder('/A')` then
     * `host: createFolder('/B')` — both folders live on the root's
     * `files` array and the second save must not displace the first.
     */
    @Test
    fun saveData_unionsParameterizedListAcrossSaves() {
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)
        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        val rootPath = "/"
        val folderA = Folder.create(context, "/A", emptyList())
        val folderB = Folder.create(context, "/B", emptyList())

        database.saveData(
            Folder.create(
                context = context,
                path = rootPath,
                files = listOf(folderA)
            )
        )
        database.saveData(
            Folder.create(
                context = context,
                path = rootPath,
                files = listOf(folderB)
            )
        )

        val rootDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = rootPath
        )
        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = rootDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val paths = database.getArrayData(arrayRef).toList()
            .map { (it as File).path.get() }
            .toSet()

        assertEquals(
            setOf("/A", "/B"),
            paths,
            "Sequential folder-creation saves must union — neither folder should overwrite the other"
        )
    }

    /**
     * Only-grow regression at the **nested** save level: the
     * `TListDto<*>` and `Composite` branches of
     * [DbEntryMapper.writeValuesToDb] used to drop
     * `getEntry` when recursing into child composites, so a fresh child
     * whose own list field is an empty `TListDto` would erase any
     * side-channel `addToArray` entries on its `(child-entity, fieldRef)`
     * array — same shrink race as
     * [saveData_doesNotShrinkParameterizedList], one level deeper.
     *
     * Setup: side-channel populates `/A`'s `files` array (as the
     * `ParentFilesRelationMaintainer` would on a normal upload). Then a
     * fresh parent (`/`) is saved with `files = listOf(folderA)`, where
     * `folderA` is a *freshly constructed* `Folder.create(files =
     * emptyList())` — that's the shape that `FolderMerger` or any cold
     * code path constructing a parent from scratch would produce.
     *
     * Pre-fix: the recursive `serialize(folderA, saveEntry)` inside
     * `writeValuesToDb`'s `TListDto` arm dropped `getEntry`, so
     * `folderA`'s own `files = emptyTListDto` save overwrote the
     * side-channel entry. Post-fix: `getEntry` propagates, the inner
     * `TListDto` branch's only-grow merge unions the existing refs, and
     * the side-channel entry survives.
     */
    @Test
    fun saveData_doesNotShrinkNestedParameterizedListsOnRecursiveSave() {
        val schemaRegistry = createFileTestRegistry()
        val context = createTestContext(schemaRegistry)
        val traitResolver = TraitResolverImpl { context }

        val database = DatabaseImpl(
            context = context,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                context = context,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver
            ),
            traitResolver = traitResolver,
            lock = NoOpDatabaseLock()
        )

        // 1. Side-channel populate `/A`'s files array with `/A/x` — the
        //    shape `ParentFilesRelationMaintainer.onDataSaved` produces
        //    when a child is uploaded into an existing folder.
        addFileToDb(
            context = context,
            database = database,
            folderPath = "/A",
            file = File.create(context, "/A/x")
        )

        // 2. Save a fresh root parent (`/`) whose `files = [folderA]`,
        //    where `folderA` is fresh with `files = emptyList()`. This
        //    exercises the nested recursive save path: outer write of
        //    `/`'s files triggers `serialize(folderA, ...)` which hits
        //    the `TListDto` branch for folderA's own (empty) files.
        val folderA = Folder.create(
            context = context,
            path = "/A",
            files = emptyList()
        )
        database.saveData(
            Folder.create(
                context = context,
                path = "/",
                files = listOf(folderA)
            )
        )

        // 3. The side-channel `/A/x` entry must still be there. Pre-fix
        //    this returned empty; post-fix it returns the original.
        val folderADataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = "/A"
        )
        val nestedRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = folderADataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val paths = database.getArrayData(nestedRef).toList()
            .map { (it as File).path.get() }
            .toSet()

        assertEquals(
            setOf("/A/x"),
            paths,
            "Recursive save of a fresh child with empty list must not erase side-channel addToArray entries on the child's array",
        )
    }

    private fun addFileToDb(
        context: TContext,
        database: DatabaseImpl,
        folderPath: String,
        file: File
    ) {
        val folderDataId = DataId.StringDataId.create(
            context = context,
            type = File.ID,
            keyValue = folderPath
        )
        database.addToArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = folderDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            value = file
        )
    }
}
