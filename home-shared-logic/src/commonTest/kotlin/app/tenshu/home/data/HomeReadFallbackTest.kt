package app.tenshu.home.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [emptyOnUnreachable], the fold both client-backed Home sources use when a read throws
 * into its collector. The rendering half (degrade to empty, never blank-screen) is the
 * long-standing contract; the cancellation half is the fix — `.catch { emit(emptyList()) }`
 * used to swallow a [CancellationException] and report "no devices" on the way out.
 */
class HomeReadFallbackTest {

    @Test
    fun anOrdinaryReadFailure_foldsToAnEmptyEmission() = runTest {
        val emissions = flow<List<String>> { throw IllegalStateException("no proxy") }
            .catch { emptyOnUnreachable("device", it) }
            .toList()

        assertEquals(listOf(emptyList<String>()), emissions)
    }

    @Test
    fun aFailureAfterRealRows_leavesTheEarlierRowsIntactAndAppendsTheEmpty() = runTest {
        val emissions = flow {
            emit(listOf("hub-1"))
            throw IllegalStateException("transport died mid-stream")
        }
            .catch { emptyOnUnreachable("hub-status", it) }
            .toList()

        assertEquals(listOf(listOf("hub-1"), emptyList()), emissions)
    }

    /**
     * The regression. Flow's `catch` does not exempt [CancellationException], so without the
     * explicit rethrow a cancelled screen folds its own teardown into a "the realm has no
     * devices" emission — which the repository reads as its fall-back-to-mock signal.
     */
    @Test
    fun cancellation_propagatesInsteadOfFoldingToEmpty() = runTest {
        var emitted = false

        val failure = assertFailsWith<CancellationException> {
            flow<List<String>> { throw CancellationException("screen closed") }
                .catch { emptyOnUnreachable("device", it) }
                .collect { emitted = true }
        }

        assertEquals("screen closed", failure.message)
        assertTrue(!emitted, "a cancelled read must not emit an empty list on its way out")
    }
}
