package app.tenshu.photos.ui.navigation

import app.tenshu.photos.ui.screens.collections.CollectionSection
import kotlinx.serialization.Serializable

/**
 * Photos navigation destinations. Custom KMP navigation (`:ui-core` `KmpNavHost`) over a plain
 * `mutableStateListOf` back stack — the shape every sibling app uses.
 *
 * ⚠⚠ **A route is an ADDRESS, not cargo, and that is the whole reason this file exists.** Photos
 * previously switched on a `remember`ed `Destination` whose cases carried *payloads*: a
 * `SmartAlbum` (which holds a `List<Photo>`) and a `ViewerSource.Fixed(List<Photo>)`. That is
 * unrepresentable as a navigation route — it cannot be serialised, it cannot survive process death,
 * and it made every destination a second copy of data a view model already held.
 *
 * The payloads moved to [app.tenshu.photos.ui.screens.collections.CollectionsViewModel] and the search /
 * album view models, which live at the shell and outlive any one entry. Routes carry ids.
 *
 * ⚠ **This did NOT cost PH-3's one-pass read.** `Smart`'s old KDoc justified carrying its photos —
 * *"re-deriving them on open would walk the realm a second time"* — and that reasoning was sound
 * against the alternative it imagined (re-querying by id). It is not an argument against holding
 * them in a view model, which is where they already were: `CollectionsViewModel.state` computes every
 * derived section from one feed read and keeps them. [Smart] looks its album up there.
 */
@Serializable
sealed interface Destination {

    /** A bottom-nav / rail destination. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Feed : TopLevel

    @Serializable
    data object Collections : TopLevel

    /**
     * Settings — ⚠⚠ **not a tab** since CR-D8. Pushed from the ⚙ in the feed's top app bar, so Back
     * returns to the feed instead of leaving the app, and selecting a tab clears it like any other
     * pushed screen. Its own pushes, [Licences] and [Trash], are unchanged.
     */
    @Serializable
    data object Settings : Destination

    /**
     * Search (S2). ⚠ A destination rather than a bar inside the feed: the results are a *different*
     * list with a badge per row, and overlaying them on a paged, day-grouped grid would mean the
     * feed's pager and the search's flat list fighting over the same scroll.
     */
    @Serializable
    data object Search : Destination

    @Serializable
    data object Trash : Destination

    /** Settings → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination

    /**
     * The duplicate review surface. ⚠ A destination of its own rather than a [Smart] album: a smart
     * album is a flat list of photos, and this is a list of *groups* with an action per group.
     */
    @Serializable
    data object Duplicates : Destination

    /**
     * One section of the Collections tab in full — its *View all* (`photos-app.md` §35).
     *
     * ⚠ Which section, not what is in it: the page reads `CollectionsViewModel.state`, as [Smart] does.
     */
    @Serializable
    data class Section(val section: CollectionSection) : Destination

    /** A user-made album. ⚠ `title` rides along only so the bar can render before the read lands. */
    @Serializable
    data class Album(val albumId: String, val title: String) : Destination

    /**
     * A derived section — a place, a year, videos.
     *
     * ⚠ The id, not the album: the photographs are in `CollectionsViewModel.state`, computed once from
     * the feed read that the Albums tab already performed.
     */
    @Serializable
    data class Smart(val smartId: String) : Destination

    /**
     * The full-screen viewer (S3).
     *
     * ⚠⚠ Carries **which list and which position**, not the list itself. Since PH-8 the viewer is a
     * pager over the same rows the grid showed, so what it needs is where the user tapped and in
     * which collection — [PhotoCollection] names the collection, the shell resolves it.
     *
     * ⚠ There is no `origin` field any more. It existed to hand-roll "Back returns to the grid you
     * came from"; a real back stack knows that, and a route that has to be told where it came from
     * is a back stack with extra steps.
     */
    @Serializable
    data class Viewer(val collection: PhotoCollection, val index: Int) : Destination

    /**
     * The photo editor (`photos-editing.md` ED-1) on the photograph at [path]. ⚠ The [Photo] itself is
     * held by the shell, for [Viewer]'s reason: rows never enter a route.
     */
    @Serializable
    data class Editor(val path: String) : Destination
}

/**
 * Which list the viewer is paging over.
 *
 * ⚠⚠ Two cases, and the distinction is **live vs snapshot**, not which screen opened it.
 *
 * [Feed] is read straight from the feed view model on every recomposition, so `loadMore` appending
 * to it makes the swipe endless (PH-8). [Fixed] is a snapshot the shell is holding — search hits, an
 * album's rows, a derived section — captured at the moment the user tapped, because those lists are
 * whole already and paging past their end means nothing.
 *
 * ⚠ Why not name the source (`Search`, `Album(id)`, …)? Because the viewer would then have to reach
 * into whichever view model owns that list, and those live in *other* nav entries with their own
 * `ViewModelStore`s. Reaching across would either fail or quietly construct a second view model and
 * re-read. The snapshot is held once by the shell, which outlives every entry.
 */
@Serializable
sealed interface PhotoCollection {

    /** The main feed. ⚠ Live: it grows under the viewer as `loadMore` appends. */
    @Serializable
    data object Feed : PhotoCollection

    /**
     * A snapshot the shell captured when the user tapped.
     *
     * ⚠ Carries no id: only one viewer is open at a time, so one slot is all there is to hold. The
     * rows themselves never enter the route.
     */
    @Serializable
    data object Fixed : PhotoCollection

    /**
     * The copy the editor just saved (`photos-editing.md` ED-1), opened on its own.
     *
     * ⚠⚠ Its own slot, not [Fixed]: the viewer the editor was opened from is still on the back stack,
     * and if it was paging a [Fixed] snapshot, overwriting that one slot would swap its list for the
     * copy alone when the user came back to it.
     */
    @Serializable
    data object Edited : PhotoCollection
}

/**
 * Switch to the tab [destination]. ⚠ Selecting a tab CLEARS the stack rather than pushing: tabs are
 * peers, and pushing them would make Back walk a history of tab switches instead of leaving the app.
 */
fun MutableList<Destination>.selectTop(destination: Destination.TopLevel) {
    if (lastOrNull() != destination) {
        clear()
        add(destination)
    }
}
