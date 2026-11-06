package org.tenshu.file.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A cancelled upload never leaves a `File` row without a journal entry behind it
 * (`drive-synced-folders-phase2.md` §4.1b).
 *
 * ⚠⚠ The row is saved before the content is journalled. A cancellation between the two — a user's
 * Stop, the OS stopping Drive's backup job when Wi-Fi drops, any wire cancel — used to leave a row
 * naming content that nothing would ever upload, and Synced Folders' next pass then got
 * `AlreadyExists` for that file and skipped it for good. The span is non-cancellable now; the inline
 * chunk upload after it is not, and the journal is what retries it.
 */
class UploadCancelKeepsRowAndJournalTogetherTest {

    /**
     * Cancels the op's OWN coroutine the moment the journal write starts, then takes a while.
     * ⚠ The op's job, captured before the row is saved — cancelling the test's caller does not reach
     * the op (it runs on its own scope), and a test that cancelled only that passed without the fix.
     */
    private class CancellingEmitter : FileContentSyncEmitter {
        var upload: Job? = null

        override suspend fun <T> withContentEmitOwnership(
            targetEntityId: DataId,
            propertyId: String,
            block: suspend () -> T,
        ): T {
            upload = currentCoroutineContext()[Job]
            return block()
        }
        val journalled = mutableListOf<String>()
        val entered = mutableListOf<String>()
        val journalDone = CompletableDeferred<Unit>()
        val emitted = mutableListOf<String>()

        override suspend fun journalFileContent(targetEntityId: DataId, propertyId: String, filePath: String) {
            entered += filePath
            upload?.cancel()
            // A suspension AFTER the cancel: inside a cancellable span this throws and the entry is
            // never written, which is the defect.
            try {
                delay(10)
                journalled += filePath
            } finally {
                journalDone.complete(Unit)
            }
        }

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            emitted += filePath
            return true
        }

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = true
    }

    @Test
    fun aCancelBetweenTheRowAndTheJournal_stillLeavesTheIntentJournalled() = runTest {
        val emitter = CancellingEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        val job = launch {
            // The op ends cancelled; whatever that looks like to the caller is not the question.
            runCatching { proxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to "/".toTString(proxy.tContext),
                    UploadFile.Params.FILE_NAME to "photo.jpg".toTString(proxy.tContext),
                    UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(proxy.tContext),
                ),
            ) }
        }
        job.join()
        // The op may finish on its own scope and dispatcher after the caller is gone; wait for the
        // journal write to end either way (on real time — it may not be on the test scheduler).
        withContext(Dispatchers.Default) { withTimeout(10_000) { emitter.journalDone.await() } }

        assertTrue(emitter.upload?.isCancelled == true, "the cancel never reached the op — this proves nothing")
        val row = proxy.executionContext.database.getData(
            DataId.StringDataId.create(context = proxy.tContext, type = File.ID, keyValue = "/photo.jpg"),
        )
        assertNotNull(row, "the row was saved before the cancel, which is the case under test")
        assertEquals(listOf("/photo.jpg"), emitter.entered, "the journal write was never reached")
        assertEquals(
            listOf("/photo.jpg"),
            emitter.journalled,
            "a row was left naming content nothing will upload — the next backup skips this file forever",
        )
    }
}
