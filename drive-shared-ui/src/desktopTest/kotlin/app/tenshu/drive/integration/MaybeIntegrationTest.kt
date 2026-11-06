package app.tenshu.drive.integration

import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MaybeIntegrationTest {

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.maybe.test")
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun testSearchSelectGetChain() = runBlocking {
        val client: TenshuClient = appKoin.get()

        // 1. Setup data: Create a file in root
        val fileSystem = client.fileService.defaultFileSystem
        fileSystem.uploadFile(
            parentPath = "/",
            fileName = "test-file.txt",
            data = byteArrayOf(1, 2, 3)
        )

        // 2. search → Maybe.select → get: `search` returns `Maybe<Entity<File>>` and
        // `Maybe<T>.select` is a typed chain step.
        //
        // `search<File>` is typed `Maybe<Entity<File>>` because the engine hands back an
        // `EntityDto<*>` implementing `Entity<*>`, not the concrete `File` interface.
        // `.select { fields(PATH) }` routes through `MaybeSelect` (the Maybe-shaped twin of
        // `Select`), preserving the wrapper shape. The path is read through Entity's generic
        // field accessor — the projected value lives there even when the concrete `File`
        // interface isn't visible at the static level.
        val result: Entity<File>? = fileSystem.root.files
            .search<File> { path equalTo "/test-file.txt" }
            .select { fields(File.Field.PATH) }
            .resolve()
            .fold(
                onSuccess = { it },
                onFailure = { null }
            )

        assertNotNull(result)
        assertEquals(
            "/test-file.txt",
            result.getFieldValue<TString>(File.Field.PATH).get()
        )
    }
}
