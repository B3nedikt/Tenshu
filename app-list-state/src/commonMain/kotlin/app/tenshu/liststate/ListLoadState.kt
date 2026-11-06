package app.tenshu.liststate

/**
 * The outcome of a list read that has not necessarily produced a list.
 *
 * Exists because a `Flow<List<T>>` **cannot express the third state**, and the repository reads
 * that returned one were folding `TResult.Failure` into `emptyList()` — so every consumer rendered
 * a failed read as an assertion that the user has nothing. That is the same family as iOS's
 * `FilesView` "empty == loading", R4's "no signal == nothing to report", and the paged listing's
 * "a failed refresh arrives with zero items": **absence of data is not evidence of absence of
 * data.**
 *
 * ⚠ A consumer that only wants the rows should not reach for [items] and forget the rest — that
 * is exactly the collapse this type exists to prevent. Render all three.
 */
sealed interface ListLoadState<out T> {

    /** The read is in flight and has produced nothing yet. Not the same as "there is nothing". */
    data object Loading : ListLoadState<Nothing>

    /** The read answered. `items` may legitimately be empty — *this* is the real zero-state. */
    data class Loaded<out T>(val items: List<T>) : ListLoadState<T>

    /**
     * The read failed. Deliberately carries no error: no consumer branches on which failure it
     * was, and a surface that renders one would be printing internals at a user.
     *
     * ⚠ Nothing here offers a retry, and that is not an oversight — these reads are reactive
     * `flow()` subscriptions, which retry themselves (`core-client-resubscription.md` R1b, waking
     * on the reconnect edge since R5). The honest message is "this could not be loaded, still
     * trying", not a button that duplicates what is already happening. The *paged* listing is the
     * one that needs a button, because Paging does not retry an error on its own.
     */
    data object Failed : ListLoadState<Nothing>

    companion object {
        /** The rows if there are any, else empty — for call sites that genuinely only map rows. */
        fun <T> ListLoadState<T>.itemsOrEmpty(): List<T> = (this as? Loaded)?.items ?: emptyList()

        /**
         * Project the rows, carrying [Loading]/[Failed] through untouched — the shape a repository
         * needs when a stored row maps to a presentation model on the way out.
         *
         * ⚠ Use this rather than `itemsOrEmpty().let(transform)` in a *read*: the latter silently
         * relabels a failure as an answered-and-empty read, which is the whole collapse this type
         * exists to prevent. `itemsOrEmpty` is for the last mile, where a caller has already
         * decided it only wants rows.
         */
        fun <T, R> ListLoadState<T>.mapItems(transform: (List<T>) -> List<R>): ListLoadState<R> =
            when (this) {
                is Loaded -> Loaded(transform(items))
                Loading -> Loading
                Failed -> Failed
            }
    }
}
