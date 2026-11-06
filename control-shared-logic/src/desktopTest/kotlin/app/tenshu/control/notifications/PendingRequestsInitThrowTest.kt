package app.tenshu.control.notifications

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.sync.PollPendingGrants
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ The feed's first calls may be the ones that mint the in-process session, and on iOS a mint
 * over the not-ready stand-in (Q35/Q40) or a fenced store **throws** from `initFileService`'s
 * write (`StoreNotReady`, `ReplicaReplaced`) instead of answering a `TResult`. The feed's scope has
 * no exception handler, and on K/N an uncaught throw there aborts the process (signal 6).
 *
 * A real [DefaultTenshuClient] over an executor that throws at first, as
 * `SessionAwareLocalTaskExecutor.executor()` does when the mint throws. Every call the feed starts
 * at construction must contain it — the grant poll and **both** `Eagerly` streams (their subscribe
 * runs through the same executor) — and afterwards a later poll must still be asked and **both
 * streams subscribe again**: a stream start that threw is a failed subscribe, retried by `TList.flow`
 * (`DefaultInteractorProvider` seeds it a Failure), never an invite feed left empty until a restart.
 */
class PendingRequestsInitThrowTest {

    /** Throws while [throwing]; afterwards answers a typed failure. Records each call's terminal op. */
    private class StandInExecutor : AsyncTaskExecutor {
        @Volatile
        var throwing = true
        private val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /**
         * A copy taken under the list's lock. ⚠ Iterating a `synchronizedList` (`count`, `any`, a
         * `for`) is not itself synchronized, and the feed's poll appends from `Dispatchers.Default`:
         * an unlocked `count` threw `ConcurrentModificationException` in the full gate.
         */
        val calls: List<String> get() = synchronized(recorded) { recorded.toList() }

        override fun execute(task: TList<Data>): TResult<Data> = answer(task)
        override suspend fun executeAsync(task: TList<Data>): TResult<Data> = answer(task)

        private fun answer(task: TList<Data>): TResult<Data> {
            val op = (task.toList().lastOrNull() as? Operation)?.schema?.id?.get() ?: "?"
            recorded += "${if (throwing) "threw" else "answered"} $op"
            if (throwing) throw IllegalStateException("made up: the shared store is not ready")
            return ResultFailureDto(GenericErrorDto("made up: nothing to answer"))
        }
    }

    private fun clientOver(executor: StandInExecutor): DefaultTenshuClient {
        val registry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(syncServiceClientModule)
            registerConfig(contactServiceCommonModule)
            registerConfig(contactServiceClientModule)
            registerConfig(notificationServiceCommonModule)
            registerConfig(notificationServiceClientModule)
        }
        val context = createTestContext(
            schemaRegistry = registry,
            interactorProvider = DefaultInteractorProvider(taskExecutorProvider = { executor }),
        )
        return DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = executor,
                schemaRegistry = registry,
                tContext = context,
                requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            )
        )
    }

    private suspend fun within(what: String, done: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!done()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$what: not within 10 s", e)
        }
    }

    @Test
    fun `a session mint that throws reaches neither the first poll's nor the streams' scope, and a later poll is still asked`() = runBlocking {
        val executor = StandInExecutor()
        val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        fun uncaughtNow(): List<Throwable> = synchronized(uncaught) { uncaught.toList() }
        // ⚠ The production scope has no handler; this one records what would have reached it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
        try {
            val feed = TenshuPendingRequestsRepository(clientOver(executor), scope = scope, pollIntervalMs = 50)

            // The first poll and both streams' subscribes all met the throw.
            within("the first poll and both streams' starts were made") {
                executor.calls.count { it == "threw ${PollPendingGrants.ID}" } >= 1 &&
                    executor.calls.count { it.startsWith("threw") && !it.endsWith(PollPendingGrants.ID) } >= 2
            }
            delay(200) // what a throw would do to the scope has landed
            assertEquals(emptyList(), uncaughtNow(), "a throw reached the feed's scope: on K/N that aborts the process")
            assertEquals(emptyList(), feed.requests.value)
            assertEquals(emptySet(), feed.seenIds.value)

            // The session is now minted: the next poll is asked and answered.
            executor.throwing = false
            within("a later poll was asked") { "answered ${PollPendingGrants.ID}" in executor.calls }
            // ⚠⚠ Both streams come back on their own (1 s backoff): Android notifies invites from them.
            within("both streams subscribed again (calls: ${executor.calls})") { executor.calls.count { it == "answered ${Stream.ID}" } >= 2 }
            feed.refresh()
            within("a refresh is asked too") { executor.calls.count { it == "answered ${PollPendingGrants.ID}" } >= 2 }
            uncaughtNow().let { assertTrue(it.isEmpty(), "a throw reached the feed's scope: $it") }
        } finally {
            scope.cancel()
        }
    }
}
