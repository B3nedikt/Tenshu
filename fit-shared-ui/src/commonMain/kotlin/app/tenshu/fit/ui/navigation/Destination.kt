package app.tenshu.fit.ui.navigation

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.ui.screens.category.CategoryDetailViewModel
import app.tenshu.fit.ui.screens.category.CategoryMetric
import kotlinx.serialization.Serializable

/**
 * Fit navigation destinations (docs/proposals/fit-app.md D2/D5). Custom KMP navigation
 * (`:ui-core` `KmpNavHost`) over a plain `mutableStateListOf` back stack that screens
 * push/pop.
 *
 * The five [TopLevel] destinations are the `AdaptiveNavScaffold` bar items (Record is the
 * emphasized centre item); the detail destinations (workout / category / medical) and the
 * always-dark [RecordLive] are pushed full-screen with their own back bar and no nav
 * chrome. Onboarding is a separate gate above this shell (D5), not a destination here.
 */
@Serializable
sealed interface Destination {

    /** A bottom-nav / rail destination. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Home : TopLevel

    @Serializable
    data object History : TopLevel

    @Serializable
    data object Record : TopLevel

    @Serializable
    data object Browse : TopLevel

    @Serializable
    data object Profile : TopLevel

    @Serializable
    data class WorkoutDetail(val workoutId: String) : Destination

    /**
     * The full-screen, pan-and-zoom route map for a workout with real GPS — pushed from the
     * workout detail's inline route map. Not [TopLevel]: it is a pushed detail surface with its
     * own back bar and no nav chrome, like [WorkoutDetail] itself.
     *
     * Carries only the id: a destination is `@Serializable` and a GPS track can be hundreds of
     * points, so the entry rebuilds the *same* `WorkoutDetailViewModel` (same Koin key) and reads
     * the route off its state, exactly as [WorkoutDetail] does.
     */
    @Serializable
    data class WorkoutRoute(val workoutId: String) : Destination

    /** The always-dark in-workout surface (D7); pushed on Start, replaced by [RecordSummary] on Stop. */
    @Serializable
    data object RecordLive : Destination

    /**
     * The finish→review→save surface (RC-2, fit-app.md §13.2): route sketch + stats + editable
     * title/activity + Save/Discard over the engine's Reviewing draft. Surfaced by the shell off
     * the `RecordingState.Reviewing` flip (in-app Stop *and* the notification's Stop action).
     */
    @Serializable
    data object RecordSummary : Destination

    /**
     * Per-category detail. [categoryId] selects the metric shown: Activity → steps trend, Sleep →
     * duration trend, Body → latest weight, Vitals → latest resting HR (fit-app.md §12 B1).
     * Medical routes to its own [Medical] screen (its card shows only when records exist).
     * [metric] picks one of Activity's series (Home's cards and stat cells open active minutes,
     * distance and energy burned, fit-app.md §18.18); other categories ignore it.
     */
    @Serializable
    data class CategoryDetail(
        val categoryId: CategoryId,
        val metric: CategoryMetric = CategoryDetailViewModel.defaultMetric(categoryId),
    ) : Destination

    @Serializable
    data object Medical : Destination

    /**
     * The sharing wizard (`fit-app.md` §15.6) — what stands between the user and their workouts,
     * one step at a time. Pushed from Profile: from the Google Fit notice, and from a permanent
     * row beside the imports.
     *
     * Carries nothing: every step is derived from a live probe, and a destination that captured a
     * verdict would be describing a device state that had already moved on by the time the user
     * came back from Health Connect — which is the exact staleness this wizard exists to defeat.
     */
    /**
     * One data source's detail (`fit-data-sources-restructure.md` §4) — what a *Data sources* row
     * opens into, and where *Import your full history*, *Check sharing setup* and the Google Fit
     * notice now live.
     *
     * ⚠ Carries [deviceId] as well as [kind], because with two phones in one realm there are two
     * Health Connect rows. A destination keyed on kind alone would open one device's screen and
     * show the other's figures.
     *
     * ⚠ Null [deviceId] means a source with no registry row — Tenshu Health, which is a
     * projection over this device's own recordings (D9/D11).
     */
    @Serializable
    data class DataSourceDetail(
        val kind: DataSourceKind,
        val deviceId: String? = null,
    ) : Destination

    /**
     * The batch-import history (`fit-data-sources-restructure.md` §4).
     *
     * Carries nothing: Takeout is an act rather than a standing connection, so there is no source
     * to identify — the screen reads every `TAKEOUT` run there is (D11).
     */
    @Serializable
    data object BatchImport : Destination

    @Serializable
    data object SharingWizard : Destination

    /** Profile → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination
}
