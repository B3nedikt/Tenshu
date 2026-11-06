package app.tenshu.photos.ui.screens.feed

import kotlinx.coroutines.CompletableDeferred

/**
 * "The feed's first rows have landed" — what a launch's other reads wait for (`photos-app.md` §38,
 * fix 2).
 *
 * ⚠⚠ Why anything waits. Every read Photos makes is answered by the same Control, and the feed's
 * first page is a whole-realm walk there. On the maintainer's phone a launch issued, within ~0.2 s of
 * the page, the scrubber's whole-library capture times, Collections' six reads and the content
 * index's whole-realm projection — and the page took 3.5–7.9 s sharing Control with them. Holding them
 * until the rows land measured −4 s to the first rows (§37.2). They are not dropped: each runs the
 * moment this completes.
 *
 * ⚠ Marked by [PhotosFeedViewModel] when its first photograph passes through the paged stream, so an
 * empty library or a failed first read never marks it. What waits must therefore be something that
 * is useless without rows — the scrubber's marks — or something with its own way in: Collections
 * still refreshes the moment its tab is opened.
 *
 * ⚠ One per app, shared through DI (Koin `single`): it is the feed view model that marks it and the
 * Collections view model that waits on it, and they are created separately. Once marked it stays
 * marked for the process, so a view model created later (a shell recreated after its view models
 * were cleared) starts at once rather than waiting for a feed that has already loaded.
 */
class FeedFirstPage {
    private val landed = CompletableDeferred<Unit>()

    /** The first rows are here. Idempotent — every row after the first is a no-op. */
    fun markLanded() {
        landed.complete(Unit)
    }

    val hasLanded: Boolean get() = landed.isCompleted

    /** Suspends until [markLanded]. */
    suspend fun await() = landed.await()
}
