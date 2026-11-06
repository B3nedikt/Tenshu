package app.tenshu.drive.integration

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertTrue

class UploadFileIntegrationTest {

    private lateinit var appKoin: AppIntegrationKoin

    // See OfflinePreferencesIntegrationTest: `by inject()` reads the global container.
    private val filesRepository: FilesRepository get() = appKoin.get()

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun uploadFile_appRoundTrip_failsIfAlreadyExists() = runBlocking {
        appKoin = startAppIntegrationKoin()

        val fileName = "test.txt"
        val parentPath = "/"
        val uri = "/local/test.txt"

        // 1. First upload
        val result1 = filesRepository.uploadFile(uri, parentPath, fileName)
        assertTrue(
            result1 is RepositoryResult.Success,
            "First upload should succeed but was $result1"
        )

        // 2. Second upload with same name
        val result2 = filesRepository.uploadFile(uri, parentPath, fileName)

        assertTrue(result2 is RepositoryResult.Error, "Second upload should fail")
        val repoError = result2.error
        assertTrue(
            repoError is RepositoryError.AlreadyExists,
            "Error should be AlreadyExists but was $repoError"
        )
    }
}
