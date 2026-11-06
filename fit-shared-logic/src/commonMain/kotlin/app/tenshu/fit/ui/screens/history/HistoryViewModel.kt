package app.tenshu.fit.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * One day's worth of workouts. [daysAgo] is relative to the dataset's today — the UI
 * localises 0 → "Today", 1 → "Yesterday", else weekday + date.
 *
 * ⚠ Still produced, and still the shape **SwiftUI** History renders (`fit-ios/HistoryView.swift`).
 * The Compose feed no longer uses it: a day straddles a page boundary, so a paged feed emits its
 * headers per row rather than grouping ahead of time (see [HistoryViewModel.workouts]).
 */
data class HistoryDayGroup(
    val date: LocalDate,
    val daysAgo: Int,
    val workouts: List<Workout>,
)

data class HistoryUiState(
    /** This week's roll-up card (trailing 7 days incl. today). */
    val weekWorkoutCount: Int = 0,
    val weekDistanceM: Int = 0,
    val weekDurationSec: Int = 0,
    /**
     * Newest day first; workouts inside a day newest first.
     *
     * ⚠ Bounded by the repository's list window — this is the *unpaged* feed, kept for the iOS
     * surface. Compose reads [HistoryViewModel.workouts] instead.
     */
    val days: List<HistoryDayGroup> = emptyList(),
)

/**
 * History (Ph3, docs/proposals/fit-app.md §2 screen 5): week summary + workout feed.
 *
 * Two feeds, deliberately: [workouts] is the paged one Compose renders, [uiState] carries the week
 * roll-up (which cannot be summed from pages) plus the day-grouped list SwiftUI still renders.
 */
class HistoryViewModel(
    private val repository: FitRepository,
) : ViewModel() {

    /**
     * The dataset's today — the feed's day headers are relative to it.
     *
     * ⚠⚠ A **getter**, not a captured `val`. It was captured here, and a view model outlives
     * midnight as readily as the process-scoped repository does: on 2026-08-13 a Fit process that
     * had been up since the previous evening drew `Today` over the whole of 12 Aug (the date chip
     * beside the header still read *Aug 12*, which is how it was caught). Every read of this — the
     * per-row header, the week roll-up window — must be a fresh one. See [FitRepository.today].
     */
    val today: LocalDate get() = repository.today

    /**
     * The Compose feed: every workout, newest first, a page at a time
     * (`fit-history-paging-handover.md`). Replaces reading [uiState]`.days`, which could only ever
     * show the repository's list window.
     *
     * ⚠⚠ **`cachedIn`, which this deliberately did NOT have until 2026-08-12.** The old note here
     * said `cachedIn` drops the refresh `load()` on every generation after the first, so a newly
     * recorded workout would not appear until the tab was re-entered. That was true of Drive when
     * it was measured on-device in 2026-07-10, and it is where Fit inherited the rule. It has since
     * **stopped being true** — `core-paging-scroll-restoration.md` §0 has the measurements, and
     * `CachedInRefreshContractTest` is the test the rule never had.
     *
     * What it buys is the reported bug: the loaded pages outlive the composition, so opening a
     * workout and coming back restores a deep-scrolled feed **in place** instead of refreshing from
     * page 0 and letting `LazyListState` clamp its restored index into a one-page list. Pinned by
     * [HistoryNavRoundTripTest]; remove the call and it fails.
     *
     * ⚠ `HistoryFeedReactivityTest` is the other half — a workout saved while History is open must
     * still appear. **If it goes red, the ban is back**: put `cachedIn` away and read
     * `core-paging-scroll-restoration.md` §7, which holds the design that routes around it.
     *
     * ⚠ Q1, still open: this retains every loaded page for the view model's life.
     *
     * ⚠ Not a `StateFlow`: a `PagingData` is a stream of *events* for one presenter, not a value to
     * replay. Exposing the cold flow is what lets `collectAsLazyPagingItems()` own the generation.
     */
    val workouts: Flow<PagingData<Workout>> = repository.workoutsPaged().cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> get() = _uiState

    init {
        // ⚠ The week roll-up must NOT come from [workouts]: a `PagingData` cannot be summed (only
        // the loaded pages exist at any moment, so the total would change as the user scrolls).
        // It rides the bounded list read, which every other Fit surface already holds open — so
        // this collect is shared, not a second trip to the store. The same read still feeds
        // `days` for iOS.
        viewModelScope.launch {
            repository.workouts().collect { workouts ->
                val sorted = workouts.sortedByDescending { it.start }
                val thisWeek = sorted.filter { it.start.date.daysUntil(today) in 0..6 }
                _uiState.value = HistoryUiState(
                    weekWorkoutCount = thisWeek.size,
                    weekDistanceM = thisWeek.sumOf { it.distanceM ?: 0 },
                    weekDurationSec = thisWeek.sumOf { it.durationSec },
                    days = sorted
                        .groupBy { it.start.date }
                        .entries
                        .sortedByDescending { it.key }
                        .map { (date, dayWorkouts) ->
                            HistoryDayGroup(
                                date = date,
                                daysAgo = date.daysUntil(today),
                                workouts = dayWorkouts,
                            )
                        },
                )
            }
        }
    }
}
