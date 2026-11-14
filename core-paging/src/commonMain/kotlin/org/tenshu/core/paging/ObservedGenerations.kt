package org.tenshu.core.paging

import androidx.paging.PagingData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Why a reactive listing built a `Pager` ([reloadSignals]).
 *
 * - [PROVISIONAL] — read before the listing's change observer was confirmed: [pagingSource]'s
 *   immediate first read, or [keysetPagingSource]'s deadline read. A write that lands before the
 *   confirmation is signalled to nobody, so this generation is **always** followed by an
 *   [OBSERVED] one, whenever the confirmation arrives.
 * - [OBSERVED] — the confirmation itself, or a settled change after it.
 */
internal enum class Reload { PROVISIONAL, OBSERVED }

/**
 * Told about every `PagingData` a listing emits from a [Reload.PROVISIONAL] generation. Installed by
 * [fromTheObservedGeneration] in the collector's context; production never installs one, and then
 * labelling costs one context lookup per generation.
 */
class ProvisionalGenerationListener internal constructor(
    internal val onProvisional: (PagingData<*>) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProvisionalGenerationListener>
}

/** Labels a [Reload.PROVISIONAL] generation's `PagingData` for a listening collector. */
internal fun <R : Any> Flow<PagingData<R>>.labelled(reload: Reload): Flow<PagingData<R>> =
    if (reload == Reload.OBSERVED) {
        this
    } else {
        onEach { currentCoroutineContext()[ProvisionalGenerationListener]?.onProvisional?.invoke(it) }
    }

/**
 * The listing from its first **observed** generation on — every [Reload.PROVISIONAL] one dropped.
 *
 * For a test that drives a live listing with `androidx.paging.testing.asSnapshot`. ⚠⚠ `asSnapshot`'s
 * `SnapshotLoader` holds an item index across a suspension: it reads `size`, asks for the next item,
 * then `peek`s the index it computed. A new generation landing in between resets the presenter to
 * one page, and the `peek` throws `IndexOutOfBoundsException: Index: 20, Size: 20` out of
 * `PageStore.checkIndex`. A [pagingSource] listing **always** emits a second generation — the
 * observer's confirmation, one proxy round trip after the first read — so whether that lands before
 * the scroll or inside it is a race the test cannot win by being fast. Starting from the observed
 * generation removes the race rather than shortening it: after it, a new generation can only come
 * from a write the test itself made.
 *
 * ⚠ Not for production UI: it withholds the first page until the observer is confirmed, which is
 * the trade [pagingSource] declines for folder listings (see [reloadSignals]). A flow that is not a
 * [pagingSource]/[keysetPagingSource] listing passes through unchanged — nothing labels it.
 *
 * ⚠ A listing whose observer is never confirmed (a refused subscribe) never emits here. Bound the
 * collection; the bound then names the missing confirmation rather than a scroll that went wrong.
 */
fun <T : Any> Flow<PagingData<T>>.fromTheObservedGeneration(): Flow<PagingData<T>> = flow {
    // Identity is the point: `PagingData` keeps `Any`'s equality, and one listing's generations are
    // distinct objects. Written upstream before the element is emitted, read downstream after it is
    // received, so the label is always there by the time `filter` looks.
    val provisional = MutableStateFlow<Set<PagingData<*>>>(emptySet())
    val listener = ProvisionalGenerationListener { data -> provisional.update { it + data } }
    emitAll(this@fromTheObservedGeneration.flowOn(listener).filter { it !in provisional.value })
}
