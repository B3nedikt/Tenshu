package app.tenshu.photos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.count
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.equalTo
import org.tenshu.core.getOrNull
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.category
import org.tenshu.core.file.files
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `resolve()` — the value terminal (`core/Resolve.kt`).
 *
 * ⚠⚠ The gap it closes is narrow and was easy to misread. `count()` has existed, been implemented,
 * and carried a proxy-side short-circuit the whole time; what it lacked was **a way for a client to
 * ask for the answer**. Dispatch is chosen by the terminal's traits, `Count` carries none by design,
 * and a trait-less terminal routes to the synchronous executor — which on a client throws at the
 * first non-pure op in the chain. Reading that failure as "count cannot be executed remotely" is
 * the mistake this test exists to make impossible to repeat.
 */
class ResolveValueChainTest {

    private class Stack(scope: CoroutineScope) {
        val proxy: TestProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also { it.executionContext.initFileService() }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )

        suspend fun seed(name: String, capturedAtMs: Long) {
            assertTrue(
                client.fileService.defaultFileSystem.uploadFile(
                    "/", name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
                ).let { it is TResult.Success },
                "seeding /$name should succeed",
            )
        }
    }

    private suspend fun Stack.imageCount(): Long? =
        client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .filter<File> { category equalTo FileCategory.Image }
            .filter<File> { notTrashed() }
            .count()
            .resolve()
            .getOrNull()
            ?.get()

    @Test
    fun aFilteredCountResolvesToItsValue() = runTest {
        val stack = Stack(this)
        stack.seed("a.jpg", 3_000L)
        stack.seed("b.jpg", 2_000L)
        stack.seed("c.jpg", 1_000L)

        assertEquals(3L, stack.imageCount())
    }

    /**
     * ⚠ The count must answer the FILTER's question, not the array's. An implementation that
     * short-circuited to the raw member-hash count would return every file — which is right for
     * `Folder.FileCount` and wrong here, and the two are one `filter` apart.
     */
    @Test
    fun theCountIsOfTheFilteredChain_notOfEveryFileInTheRealm() = runTest {
        val stack = Stack(this)
        stack.seed("a.jpg", 3_000L)
        stack.seed("b.jpg", 2_000L)
        stack.seed("notes.txt", 1_000L)

        assertEquals(2L, stack.imageCount(), "the text file is not an image")
    }

    @Test
    fun anEmptyMatchResolvesToZero_notToAFailure() = runTest {
        val stack = Stack(this)
        stack.seed("notes.txt", 1_000L)

        assertEquals(0L, stack.imageCount())
    }

    /**
     * ⚠⚠ Two independent implementations of the same question must agree.
     *
     * ⚠ This test previously compared `resolve()` against `TenshuPhotosRepository.feedTotal()` —
     * which was fine while the repository counted with `getAll`, and became **circular** the moment
     * it switched to `resolve()`. A green test comparing an implementation with itself is worse
     * than none, because it reads as a cross-check. The materialised count is spelled out here so
     * the two sides stay genuinely different.
     */
    @Test
    fun theValueTerminalAgreesWithAMaterialisedCount() = runTest {
        val stack = Stack(this)
        stack.seed("a.jpg", 3_000L)
        stack.seed("b.jpg", 2_000L)
        stack.seed("c.jpg", 2_000L)
        stack.seed("notes.txt", 1_000L)

        val materialised = stack.client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .filter<File> { category equalTo FileCategory.Image }
            .filter<File> { notTrashed() }
            .select { fields(File.Field.PATH) }
            .getAll()
            .getOrNull()
            ?.toList()
            ?.size
            ?.toLong()

        assertEquals(3L, materialised, "three images, one text file")
        assertEquals(materialised, stack.imageCount())
    }
}
