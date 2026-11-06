package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.ErrorEventRecorder
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.FileService
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `ExpectedOutcome` must reach the caller as a typed failure and must **not** reach the
 * diagnostic log.
 *
 * ⚠⚠ Both halves matter, and only together. Suppressing the record while also swallowing the
 * failure would break every caller that branches on it (the upload conflict dialog, Synced-Folders'
 * silent `Skipped`); recording it while delivering it is the status quo that filled a real device's
 * log with **732** of these out of 1 249 events, and buried a feature that was throwing on every
 * call for a day underneath them.
 */
class ExpectedOutcomeNotRecordedTest {

    @Test
    fun anAlreadyExistsIsDeliveredToTheCallerButNotRecorded() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val recorded = mutableListOf<String>()
        proxy.executionContext.errorEventRecorder = ErrorEventRecorder { _, operationId, _ ->
            recorded.add(operationId)
        }

        val fs = fileService(proxy).defaultFileSystem
        assertTrue(fs.createFolder("/dup") is TResult.Success, "the first create succeeds")

        val second = fs.createFolder("/dup")

        // Half one: the caller still receives the typed failure it branches on.
        val error = (second as? TResult.Failure)?.error
        assertTrue(
            error is AlreadyExistsError,
            "the caller must still receive the typed AlreadyExistsError, got $second",
        )
        assertEquals(
            emptyList(), recorded,
            "an ExpectedOutcome must not be minted as an ErrorEvent — it is the operation's " +
                "ordinary answer, and 732 of them were burying a real fault on a live device",
        )
    }

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        ).getService(FileService.ID)
    }
}
