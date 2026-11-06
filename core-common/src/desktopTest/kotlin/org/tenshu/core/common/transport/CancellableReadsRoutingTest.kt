package org.tenshu.core.common.transport

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskInteractor
import org.tenshu.core.WithTaskElement
import org.tenshu.core.cancellableReads
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which executor entry an IO call reaches (`core-background-processing.md` §6.2.1): a cancellable
 * read **only** inside `cancellableReads { }`, and a task's id wins over it inside `withTask`.
 *
 * ⚠⚠ The first case is the one that protects writes: a plain call routed as a cancellable read
 * would be stopped by its caller's cancellation, and a save from a closing screen would be lost.
 */
class CancellableReadsRoutingTest {

    private class Recorder(private val delegate: CancellableReadRig) :
        CancellableLocalTaskExecutor, CancellableReadTaskExecutor {
        val calls = mutableListOf<String>()
        private fun ok(): TResult<Data> = TResult.Success.create(context = delegate.proxy.tContext, value = null)
        override fun execute(task: TList<Data>): TResult<Data> = ok().also { calls += "execute" }
        override suspend fun executeAsync(task: TList<Data>): TResult<Data> = ok().also { calls += "plain" }
        override suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data> =
            ok().also { calls += "task" }
        override suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String?): TResult<Data> =
            ok().also { calls += "task:$taskId" }
        override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> =
            ok().also { calls += "read" }
    }

    private suspend fun runIoCall(rig: CancellableReadRig, recorder: Recorder) {
        val provider = DefaultInteractorProvider(taskExecutorProvider = { recorder })
        val ro = object : OperationRo(
            requestChain = LocalRequestChain(recorder, rig.proxy.tContext)
                .addArray(OperationDto(schema = CancellableReadRig.STEP, params = emptyMap())),
            context = rig.proxy.tContext,
        ) {
            override val schema = CancellableReadRig.STEP
        }
        @Suppress("UNCHECKED_CAST")
        (provider.getInteractor<Data?>(ro) as TaskInteractor<Data?>).execute()
    }

    @Test
    fun aPlainIoCallStaysPlain() = runBlocking {
        val rig = CancellableReadRig()
        val recorder = Recorder(rig)
        runIoCall(rig, recorder)
        assertEquals(listOf("plain"), recorder.calls)
    }

    @Test
    fun insideCancellableReadsItIsACancellableRead() = runBlocking {
        val rig = CancellableReadRig()
        val recorder = Recorder(rig)
        cancellableReads { runIoCall(rig, recorder) }
        assertEquals(listOf("read"), recorder.calls)
    }

    @Test
    fun withTaskWinsOverCancellableReads() = runBlocking {
        val rig = CancellableReadRig()
        val recorder = Recorder(rig)
        cancellableReads {
            withContext(WithTaskElement("t-1")) { runIoCall(rig, recorder) }
        }
        assertEquals(listOf("task:t-1"), recorder.calls)
    }
}
