package app.tenshu.drive.integration.utils

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/**
 * Drives a `Flow<PagingData<T>>` through a real [PagingDataPresenter] and **records the timeline**
 * of everything the differ is told and everything it says back.
 *
 * ⚠⚠ **Why this exists rather than another gate hunt.** The listing-never-settles hang
 * (`docs/tech-debt.md` § "The hang, narrowed to one seam") has survived three measurements. What is
 * known: every `load()` returns, nothing is running at the timeout, and the generation swap that
 * looked like the trigger is survivable on demand (forced 3/3 green). What is *not* known is which
 * load state stops arriving — and [androidx.paging.testing.asSnapshot], the API the failing test
 * uses, deliberately exposes none of that: it returns a list or hangs.
 *
 * This probe is the same pipeline with the lid off. On a timeout it prints, in order and with
 * timestamps: every `PagingData` generation, every [CombinedLoadStates] emission, and every
 * [PagingDataEvent] the presenter received. That is enough to say which of the three stopped —
 * generations, load states, or presentation events — which is the one fact the hang has never given
 * up.
 *
 * ⚠ It is a **diagnostic**, not a guard. It asserts only that the listing settles, and settling is
 * already asserted (better, with real data) by the tests that use `boundedSnapshot`. Keep it out of
 * anything that gates a merge; its value is that it can be looped cheaply while a hunt is on.
 *
 * ⚠ The presenter runs on the caller's dispatcher by default rather than `Dispatchers.Main`: the
 * production default is Main (real on iOS), which off-device would need `setMain` and would put a
 * second, unrelated race (`docs/tech-debt.md`, the thirteenth victim) inside a probe for this one.
 */
class PagingSettleProbe<T : Any>(
    private val scope: CoroutineScope,
    private val mainContext: CoroutineContext = Dispatchers.Unconfined,
) {

    private val started = System.nanoTime()
    private val timeline = mutableListOf<String>()

    private fun record(kind: String, detail: String) {
        val atMs = (System.nanoTime() - started) / 1_000_000
        synchronized(timeline) { timeline += "%6d ms  %-12s %s".format(atMs, kind, detail) }
    }

    private val presenter = object : PagingDataPresenter<T>(mainContext) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) {
            record("event", event::class.simpleName ?: "?")
        }
    }

    /**
     * Collects [flow] and suspends until the differ's refresh reaches a non-[LoadState.Loading]
     * state — the same condition `asSnapshot` waits on internally.
     *
     * @throws AssertionError with the full timeline when it does not settle within [timeoutMillis].
     */
    suspend fun awaitSettle(flow: Flow<PagingData<T>>, timeoutMillis: Long = 30_000): List<T> {
        val states = scope.launch {
            presenter.loadStateFlow.collect { record("loadState", describe(it)) }
        }
        val collection = scope.launch {
            flow
                .collectLatest { generation ->
                    record("generation", "collectFrom begin")
                    presenter.collectFrom(generation) // never returns for a live generation
                }
        }
        return try {
            withTimeout(timeoutMillis) {
                presenter.loadStateFlow.first { it != null && it.refresh !is LoadState.Loading }
                presenter.snapshot().items
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                buildString {
                    appendLine("listing did not settle within ${timeoutMillis}ms. Timeline:")
                    synchronized(timeline) { timeline.forEach { appendLine("  $it") } }
                    appendLine("Read it as: which of generation / loadState / event stopped arriving?")
                },
                timeout,
            )
        } finally {
            states.cancel()
            collection.cancel()
        }
    }

    private fun describe(s: CombinedLoadStates?): String =
        if (s == null) "null (seed)"
        else "refresh=${s.refresh::class.simpleName} append=${s.append::class.simpleName} " +
            "prepend=${s.prepend::class.simpleName}"

    /** The timeline so far, for a caller that wants it on the success path too. */
    fun timelineSnapshot(): List<String> = synchronized(timeline) { timeline.toList() }
}
