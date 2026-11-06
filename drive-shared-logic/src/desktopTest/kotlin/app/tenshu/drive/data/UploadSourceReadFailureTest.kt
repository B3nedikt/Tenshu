package app.tenshu.drive.data

import app.tenshu.drive.domain.LogUnexpectedErrorUseCase
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ingest.backup.BackupUploadOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Service
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A platform delegate signals "this source cannot be read at all" by **throwing** — the read
 * happens while evaluating the upload op's arguments, so there is no `TResult` yet to fail. That
 * throw must become an ordinary repository failure here, because above this class it lands in an
 * unhandled `viewModelScope.launch`, and on Kotlin/Native that is `abort()`: a picked Keynote
 * package (`.key` is a *directory*) crashed Drive-on-iOS exactly this way.
 *
 * ⚠⚠ The `*_cancellationPropagates` cases are the other half of the guard, and the more fragile
 * one: a bare `runCatching` / `catch (Throwable)` here would swallow [CancellationException],
 * reporting a user-initiated upload STOP as a failure and breaking structured concurrency.
 * Delete the `catch (e: CancellationException) { throw e }` arm in `guardingSourceRead` and these
 * two must go red.
 */
class UploadSourceReadFailureTest {

    @Test
    fun uploadFile_unreadableSource_isReportedNotThrown() = runTest {
        val result = repository { IllegalStateException("File not found: /tmp/Deck.key") }
            .uploadFile(uriString = "/tmp/Deck.key", parentPath = "/", fileName = "Deck.key")

        val error = assertIs<RepositoryResult.Error>(result)
        val unknown = assertIs<RepositoryError.Unknown>(error.error)
        // ⚠ Asserts the KEY, not a message. This used to check that the platform's own text
        // survived — which the error-copy catalogue deliberately made false: `Unknown` carries an
        // `ErrorCopyKey` so a raw platform string can never reach a user. The reason still reaches
        // a developer, through the throw itself.
        assertEquals(ErrorCopyKey.UNEXPECTED, unknown.copyKey)
    }

    @Test
    fun uploadFile_cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            repository { CancellationException("upload stopped") }
                .uploadFile(uriString = "/tmp/Deck.key", parentPath = "/", fileName = "Deck.key")
        }
    }

    /** The auto-backup drain reaches the same delegate through `attemptUpload`. */
    @Test
    fun uploadForBackup_unreadableSource_isReportedNotThrown() = runTest {
        val outcome = repository { IllegalStateException("File not found: /tmp/Deck.key") }
            .uploadForBackup(parentPath = "/Backup", fileName = "Deck.key", uriString = "/tmp/Deck.key")

        assertIs<BackupUploadOutcome.Failed>(outcome)
    }

    /**
     * ⚠⚠ The reason must survive, not just the fact of failure — backup has no user standing over
     * it, so this string is the only trace a failure leaves (§8c). Replace it with a constant and
     * this goes red; that is the point.
     */
    @Test
    fun uploadForBackup_unreadableSource_carriesTheReason() = runTest {
        val outcome = repository { IllegalStateException("File not found: /tmp/Deck.key") }
            .uploadForBackup(parentPath = "/Backup", fileName = "Deck.key", uriString = "/tmp/Deck.key")

        assertEquals("File not found: /tmp/Deck.key", assertIs<BackupUploadOutcome.Failed>(outcome).reason)
    }

    /** A throwable with no message still has to say *something* a reader can act on. */
    @Test
    fun uploadForBackup_messagelessFailure_fallsBackToTheType() = runTest {
        val outcome = repository { IllegalStateException() }
            .uploadForBackup(parentPath = "/Backup", fileName = "Deck.key", uriString = "/tmp/Deck.key")

        assertEquals("IllegalStateException", assertIs<BackupUploadOutcome.Failed>(outcome).reason)
    }

    @Test
    fun uploadForBackup_cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            repository { CancellationException("upload stopped") }
                .uploadForBackup(parentPath = "/Backup", fileName = "Deck.key", uriString = "/tmp/Deck.key")
        }
    }

    private fun repository(failure: () -> Throwable) = TenshuFilesRepository(
        client = UnreachableClient,
        fileMapper = FileMapper(),
        logUnexpectedError = LogUnexpectedErrorUseCase(),
        delegate = ThrowingDelegate(failure),
    )
}

/** Every method throws — the shape of a source the host cannot turn into bytes. */
private class ThrowingDelegate(
    private val failure: () -> Throwable,
) : FilesRepositoryPlatformDelegate {

    override fun getPlatformFileName(uriString: String): String = uriString.substringAfterLast('/')

    override suspend fun listFolderEntries(uriString: String): FolderListing = throw failure()

    override suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TResult<Data?> = throw failure()

    override suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TResult<Data?> = throw failure()

    override suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TaskHandle<Data?> = throw failure()

    override suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TaskHandle<Data?> = throw failure()

    override suspend fun contentHashOf(uriString: String): String? = null
}

/**
 * ⚠ Deliberately explodes on every access: an unreadable source must fail *before* the client is
 * touched, so a test that accidentally reached the proxy would fail loudly rather than hang.
 */
private object UnreachableClient : TenshuClient {
    override val environment: TenshuEnvironment get() = error("client must not be reached")
    override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(true)
    override fun <T : Service> getService(id: String): T = error("client must not be reached")
    override fun init() = error("client must not be reached")
    override fun destroy() = error("client must not be reached")
}
