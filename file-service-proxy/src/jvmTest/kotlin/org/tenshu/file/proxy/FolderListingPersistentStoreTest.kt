package org.tenshu.file.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.proxy.evaluateCallstack
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.createFileTestRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §4 of `docs/proposals/drive-folder-listing-consistency.md`: does the
 * create-while-viewing staleness reproduce against the **production store pair**
 * — `SqliteObjectStore` + `ReentrantLockDatabaseLock` — under **real
 * multi-threaded concurrency**? (The `FolderListingIdentityTest` guards run over
 * the in-memory / no-op / single-threaded pair and pass, so they can't answer
 * this.)
 *
 * Children are created concurrently on `Dispatchers.Default` while a reader
 * repeatedly runs the REAL detail chain (`search<Folder>{"/Q"}.files.getAll()`,
 * the shape `navigateTo` produces). During pure creates the visible count must be
 * monotonic non-decreasing (a lower-than-previously-seen read = a stale read =
 * the §4 symptom) and must converge to every created child.
 *
 * jvmTest (not commonTest): `SqliteObjectStore` / `ReentrantLockDatabaseLock` are
 * JVM-only.
 */
class FolderListingPersistentStoreTest {

    private fun tempDbPath(): String =
        createTempDirectory("folder-listing-sqlite").resolve("objects.db").toString()

    private suspend fun createFolder(testProxy: TestProxy, path: String) {
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(testProxy.tContext)),
        )
    }

    /** /Q's listing via the real detail chain: search root by path, then .files.getAll(). */
    private fun readQChildCount(testProxy: TestProxy): Int {
        val ec = testProxy.executionContext
        val searchResult = evaluateCallstack(
            executionContext = ec,
            contextData = rootFilesRef(testProxy),
            callstack = listOf(searchByPath("/Q")),
            allowDeferring = false,
        )
        val qEntity = (searchResult as Maybe.Some<*>).value as Data
        val filesResult = evaluateCallstack(
            executionContext = ec,
            contextData = qEntity,
            callstack = listOf(
                createFileTestRegistry().getReference(Folder.Fields.FILES),
                OperationDto(params = emptyMap(), schema = GetAll.Schema),
            ),
            allowDeferring = false,
        )
        return (filesResult as TList<*>).toList().size
    }

    @Test
    fun persistentStore_concurrentCreatesAndReads_convergeWithoutRegression() = runBlocking(Dispatchers.Default) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = openSqliteObjectStore(tempDbPath(), scope)
        val testProxy = createFileProxy(
            coroutineScope = scope,
            objectStore = store,
            lock = ReentrantLockDatabaseLock(),
        )

        createFolder(testProxy, "/Q")

        val n = 40
        val regressions = CopyOnWriteArrayList<String>()
        val readErrors = CopyOnWriteArrayList<String>()

        // Reader: hammer the detail chain while writers add children.
        val reader = scope.launch {
            var high = 0
            while (isActive) {
                val c = runCatching { readQChildCount(testProxy) }
                    .onFailure { readErrors.add(it.toString()) }
                    .getOrNull()
                if (c != null) {
                    if (c < high) regressions.add("read $c after having seen $high")
                    if (c > high) high = c
                }
                yield()
            }
        }

        // Writers: create /Q/c0..c{n-1} concurrently.
        (0 until n).map { i -> scope.launch { createFolder(testProxy, "/Q/c$i") } }.joinAll()

        delay(500) // let the reader observe the settled state
        reader.cancelAndJoin()

        val finalCount = readQChildCount(testProxy)

        assertTrue(
            readErrors.isEmpty(),
            "detail read threw under concurrency (${readErrors.size}): ${readErrors.take(3)}",
        )
        assertTrue(
            regressions.isEmpty(),
            "detail read regressed under concurrent creates (§4 symptom): ${regressions.take(5)}",
        )
        assertEquals(
            n,
            finalCount,
            "all $n concurrently-created children must be visible via the detail chain (persistent store + real lock)",
        )
    }

    private fun rootFilesRef(testProxy: TestProxy): ArrayDatabaseReference {
        @Suppress("UNCHECKED_CAST")
        val rootFolder = testProxy.executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT),
        ) as Entity<Folder>

        return ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = org.tenshu.core.collection.list.TList.ID,
                parentEntity = testProxy.executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = false,
            elementSchemaId = File.ID,
        )
    }

    private fun searchByPath(path: String) = OperationDto(
        params = mapOf(
            Search.Params.PREDICATE to listOf(
                createFileTestRegistry().getReference(File.Field.PATH),
                OperationDto(
                    params = mapOf(EqualTo.Params.OTHER to path.toTString()),
                    schema = EqualTo.Schema,
                ),
            ).toTList()
        ),
        schema = Search.Schema,
    )
}
