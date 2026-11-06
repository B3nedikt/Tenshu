package org.tenshu.core

interface StreamCallback<T : Data> {

    fun start(error: TError?)

    fun element(streamElement: StreamElement<T>)

    fun end(error: TError?)

    /**
     * The subscription was re-established against a **restarted proxy**, and the elements that
     * follow are a fresh full snapshot rather than a continuation
     * (`docs/proposals/core-client-resubscription.md` R1).
     *
     * Consumers that accumulate state from [element] MUST discard it here. A consumer merges
     * incoming values by id and only drops rows on an explicit
     * [StreamElement.Removed][org.tenshu.core.collection.list.StreamElement.Removed]; every
     * `Removed` that occurred while the transport was down is lost with the old proxy's registry,
     * so merging the new snapshot into the old state would silently resurrect deleted rows.
     * Clearing first makes the snapshot authoritative.
     *
     * Client-local and synthesised by [org.tenshu.core.common.StreamInteractorImpl] — it never
     * crosses the wire, so it costs no protocol or storage-format change. Default no-op: a
     * consumer that keeps no accumulated state has nothing to reset.
     */
    fun reset() {}

    /**
     * A **joiner's** catch-up: the elements a stream that was already live buffered, replayed to a
     * callback that registers late (`core-client-resubscription.md` R3), in order. The log starts
     * with the snapshot from when the stream first subscribed, so a consumer that renders after
     * each element would show that old state first. Default: each element in turn, as [element].
     * `TList.flow` folds them into one emission. Client-local — never crosses the wire.
     */
    fun catchUp(elements: List<StreamElement<T>>) {
        elements.forEach { element(it) }
    }
}

interface StreamInteractor<T : Data> : Interactor<T> {

    val initialResult: TResult<Data?>?

    suspend fun registerCallback(callback: StreamCallback<T>)

    suspend fun endStream(callback: StreamCallback<T>)
}
