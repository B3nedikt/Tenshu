# A fast-scroll scrubber for the Photos feed (PH-17)

**Status**: v0.1 — design, no code.
**Asked for**: *"as soon as you start scrolling, a small circle with up/down arrows shows up on the
right; moving it moves through the list significantly faster, and it gives a kind of year
indicator."*

---

## 1. What the reference apps actually do

Measured on Google Photos on a OnePlus CPH2581, 2026-08-31, by dragging the thumb and capturing
mid-drag. Four separate pieces, not one:

| Piece | Behaviour |
|---|---|
| **Thumb** | A pill with stacked ▲▼ chevrons on the right edge. Appears once the grid is scrolled; still present after ~6 s idle, so it is *scroll-activated*, not *drag-activated*. |
| **Position label** | A pill attached to the thumb, reading **month + year** (`Feb 2025`) — where the drag is *going*. |
| **Year index** | `2026` `2025` `2023` `2019` `2017` as small pills down the track. ⚠ **Only while dragging.** |
| **Header bubble** | A pill at the top reading the **full date** (`Thu, 20 Feb 2025`) — where the grid *is*. |

⚠⚠ **Two findings that are not obvious and that shape the work:**

1. **The year markers are spaced by content density, not by time.** 2026 and 2025 sit far apart;
   2023, 2019 and 2017 bunch together near the bottom. The track is a map of *where the
   photographs are*, so it needs a **per-year count**, not a min/max date.
2. **Thumbnail loading is suppressed during the drag.** Mid-scrub the grid is grey placeholders
   with a single loaded tile; they populate when it settles. Anything that decodes previews while
   the thumb moves will be slower than what it is imitating.

⚠ The two labels answer different questions and both are needed: the thumb says *where you are
going* (coarse, month), the header says *where you are* (exact, full date).

## 2. ⭐ Most of the data already exists

The expensive part of a density-spaced year index is knowing how many photographs each year holds.
**We already compute that, and hold it live.**

`RealmContentIndex` (§20 of `photos-app.md`) is a `Flow<ContentIndex?>` — a *subscription*, not a
cache, so the store owns invalidation — bounded by `shareIn(replay = 1)` +
`WhileSubscribed(5 s)` + `conflate()`. `ContentIndex.all` is **every file the walk found, newest
first**, each an `IndexedContent` carrying `createdAtMs` and `isImage`.

So the year map is `all.filter { it.isImage }.groupingBy { year(it.createdAtMs) }.eachCount()`,
plus a running total for track offsets. **No new read, and it covers the whole library rather than
the pages that happen to be loaded** — which was the thing worth checking before designing anything.

## 3. ⚠⚠ The hard part: the feed and the index are two different lists

This is the whole of the design problem, and it is not the scrubber.

- `allPhotos()` **is** index-backed: one shared subscription, every image, already sorted.
- `feedPage()` **is not**: it does a fresh `traverse()` + sort **per page**, keyset-cursored.

A scrubber is a control over *a list*. Ours has two, and they disagree about how much of the
library is in hand: the index holds all of it, the feed holds what has been paged.

Dragging the thumb to 2019 therefore means one of:

**(a) Feed renders from the index.** The grid becomes a plain scrollable list over
`ContentIndex.all`, the scrubber becomes an ordinary scrollbar, and `feedPage` retires. ⭐ Simplest
*resulting* system, and it deletes the hand-rolled pager, the `(time, skip)` cursor and the
`refreshNewest` merge along with it. ⚠ It also changes what `core-keyset-paging.md` is about — with
no keyset pager left, Photos' reason for not using `.pagingSource()` mostly evaporates.

⚠⚠ **The residency objection this section first raised is weaker than it looked — see §6.2.** The
rows are *already* resident whenever the Albums tab is open, because `allPhotos()` is index-backed.
(a) does not add the cost; it extends how long it is held.

**(b) Scrubbing re-anchors the keyset cursor.** Dragging to 2019 sets `beforeMs` to that year's end
and reloads. Cheap, fits today's architecture, no residency change. ⚠⚠ But the cursor is
**one-directional**: after re-anchoring you cannot page *back up*, so scrolling up from a scrubbed
position hits a wall the user did not create. That is a worse bug than the one being fixed.

**(c) Hybrid — index for the map, feed for the rows.** The scrubber's *track* comes from the index;
dragging maps a track offset to a date, and the feed re-anchors as in (b). Same one-directional
problem.

### 3.1 ⭐ They are not three options — (a) and keyset paging are one destination

⚠⚠ **This section was written before checking what Paging 3 actually does, and the check changes
the answer.** Three facts, read from `core-paging/PagingUtil.kt`:

| Fact | Consequence |
|---|---|
| `TenshuPagingSource` returns a non-null `prevKey` unless at position 0 | **Paging already prepends.** The one-directional wall that makes (b) unacceptable does not exist under Paging — it is a limitation of Photos' *hand-rolled* cursor, not of keyset paging |
| `jumpingSupported` is never set (Paging defaults it `false`) | Without it a scrub to 2019 would load **every intervening page**. A scrubber essentially requires it; with it, Paging invalidates and refreshes at the anchor via `getRefreshKey` |
| `enablePlaceholders = false` in `defaultPagingConfig()` | With placeholders **on** and a total, `LazyPagingItems.itemCount` is the **whole library**. And the total is something `ContentIndex` already has |

So a keyset source **+ `jumpingSupported` + placeholders** converges with (a): full-length track,
accurate year pills, a scrub that seeks and loads around where it lands. What is left:

| | (a) index-backed feed | keyset paging |
|---|---|---|
| Track length | full | full, via placeholders |
| Residency | everything, always | loaded pages + `cachedIn` |
| A long scrub | instant | a brief placeholder while it loads |
| Day grouping | over every row, on every change | over loaded pages only |
| Machinery | deletes a bespoke pager | the **shared** one Drive and Fit use |

⭐ **Built 2026-09-01** — `core-keyset-paging.md` K-1…K-4 landed all of it: a keyset source,
positional seeks, placeholders over a total, `jumpingSupported`, and Photos' feed moved onto it.

⭐ **PH-17c shipped 2026-09-01.** The track is the whole library and a long drag re-anchors in one
read. Verified on a device: one drag from 2026 reached 12 June 2018, and the placeholders filled.
§4 below is the as-built.

⚠ **PH-17c/d were unblocked but not free.** The feed pages through `:core-paging` now, and turning
the track full-length is `seekablePagingConfig()` plus a `totalCount` at one call site
(`TenshuPhotosRepository.feedPaging`). What that call site does not have yet is the count: Photos'
feed is a *filtered* whole-realm traversal, so `count()` walks the tree, and the source reads it once
per generation rather than per page for exactly that reason. Whether that read is cheap enough to sit
in the feed's load path is the open question, and it is a measurement, not a design decision.

**Recommendation: implement keyset paging (`core-keyset-paging.md`), and treat the scrubber as its
consequence.** It reaches (a)'s behaviour while keeping residency bounded, groups less, and puts
Photos back on shared machinery instead of a bespoke path — where today it is the only listing in
the repo with a hand-rolled pager.

⚠ **What is verified and what is argued.** Prepend, the missing `jumpingSupported` and the
placeholder default are read from the source. That the two *converge behaviourally* is a design
argument, not a measurement, and the placeholder flash after a long scrub is a feel question only a
device settles.

## 4. What the scrubber itself needs

Assuming a complete list:

- A `LazyGridState`-driven thumb: visible when `canScrollBackward || canScrollForward` and the grid
  has been scrolled, positioned by `firstVisibleItemIndex / totalItems`.
- Drag maps thumb offset → item index → `scrollToItem`. ⚠ Not animated: a scrub is a seek, and
  animating each step is what makes a fast-scroll feel laggy.
- **Suppress preview loading while dragging.** `PhotosFeedScreen` takes `loadPreview`; gate it on a
  `isScrubbing` flag so tiles render placeholders until the drag settles (§1, finding 2).
- The year pills come from the density map, positioned at `cumulativeCount / total` of the track.
- The header bubble already has a formatter — `dayLabel` is passed into `PhotosFeedScreen` today.

## 5. Phases

| Phase | Content |
|---|---|
| **PH-17a** | The density map: `yearIndex(): List<YearMark>` derived from `ContentIndex`, with a test that a year with no photographs produces no mark. No UI. |
| **PH-17b** | The thumb and the drag, over whatever list the feed holds. Scroll-activated, seek-not-animate, previews suppressed while dragging. |
| **PH-17c** | ⚠⚠ **The name drifted, and this row is the correction.** It was *"the year pills on the track, and the month label on the thumb"*; what shipped on 2026-09-01 under this name was the **§3 resolution** — placeholders over a total, so the track spans the library rather than the loaded window (§4). That was a precondition this table treated as a decision, not a phase. **The year pills remain unbuilt.** |
| **PH-17c′** | ✅ **2026-09-02** — the year pills on the track (§8). ⚠ The blocker this row named was wrong: the density map needs one column, not a list of photographs, and the feed's own window answers it in a single read. |
| **PH-17d** | ✅ **2026-09-01** — the date bubble beside the thumb while dragging (§7). |

⚠ PH-17b is deliberately honest-but-limited: a scrubber over the loaded pages is still useful (it is
a faster way to move through what you have) and it does not *claim* to cover the library. The year
pills are what would lie, which is why they are a separate phase behind the §3 decision.

## 6. Open questions

1. **§3 is the real question**, and it is not about scrubbing: should the feed render from the
   shared index rather than paging? That decides whether PH-17c/d are honest.
2. **Residency. ⭐ Sized 2026-08-31 — and the answer reframes the question.**

   ⚠⚠ **The cost is already paid today.** `allPhotos()` is index-backed, so opening the Albums tab
   makes the *whole* index resident already. Option (a) would not introduce the memory; it would
   only extend how long it is held — `WhileSubscribed(5 s)` after Albums, versus whenever the feed
   is open. That is a smaller change than "makes residency unconditional" implied.

   **Sizing**, at ~370–420 B/row: `IndexedContent` is three `String` refs, two `Long`s, two
   `Boolean`s and two nullable boxed longs — ~56 B of object, with the strings dominating (paths on
   the test device run ~105 chars, plus a hex `contentHash` and a `contentId`). ⭐ Corroborated
   rather than derived alone: `core-paging-scroll-restoration.md` Q1 **measured** 308 B/row for a
   comparable row. So **~8 MB of rows at 20 000 photographs, ~10–12 MB including the `byHash` map**.

   ⚠⚠ **This is an estimate, not a measurement at scale, and the distinction matters.** The
   maintainer's library is **922 photographs** (~0.4 MB) — 4.6 % of the hypothetical — so the
   device cannot answer the 20 000 question. ⚠ And the obvious measurement does not work: PSS with
   the index resident was 225 MB against 222 MB at launch, while the *Java heap fell* 47 → 25 MB
   because a GC ran. The figure is dominated by decoded bitmaps; a PSS delta cannot isolate the
   index. Confirming 8 MB needs a library that size or a synthetic fixture.

   ⚠ **And ~10 MB of rows is not the risk worth worrying about.** Whether a 20 000-item grid
   *scrolls* smoothly is a different question, unmeasured, and the likelier problem.
3. **Does the thumb hide?** Google's did not fade after 6 s of idle in the capture. Worth a longer
   observation before copying either behaviour.
4. **Videos.** The feed shows them; the year map should count them, so `isImage` is the wrong filter
   for the density map if the feed is not filtered the same way. Check before implementing.


## 4. As built — PH-17c (2026-09-01)

Three lines at one call site, and a month of measurement to know they were the right three:
`seekablePagingConfig(pageSize)`, `totalCount = { feedTotal() }`, and a placeholder branch in the
grid. `:core-paging` K-3 had already built the machinery; what was missing was a total anyone could
trust.

### 4.1 ⚠⚠ The total was the whole problem, and it took three wrong answers

The count had to equal the number of photographs the feed actually serves. With placeholders on, a
total that is too high is not a rounding error — it is grey tiles at the end of the track that
nothing will ever fill. On a real library three sources disagreed:

| | said | verdict |
|---|---|---|
| the shared content index | 922 | **right all along** |
| a filtered scan | 922 | right |
| **the paged feed** | **915** | **wrong — it was losing photographs** |

The first two explanations were confident and wrong: *"the index over-counts by 7"* and *"seven
photographs have no capture time"*. The truth was §23 — the feed's window excluded its own cursor,
so seven photographs sharing a page-boundary capture time were missing from the app entirely. After
that fix all four sources read 922 and the total became well-defined.

⚠ The lesson is not about counting. Twice a component that was behaving correctly got blamed while
the defect sat in the code being actively worked on, and only measurement separated them.

### 4.2 What it costs, honestly

`feedTotal()` is a `getAll` with a path-only projection — ~1.1 s warm for ~900 photographs — because
**there is no terminal count operation to call**. `Count` is deliberately trait-less: its one caller
is `Folder.FileCount`, a proxy-side dynamic property over a raw array, and a non-suspend resolver
cannot await a `TaskInteractor`. So a chain ending in `count()` dispatches to the synchronous
executor and cannot cross IPC.

⚠⚠ **This does not scale.** It ships one path per photograph over Binder, whose transaction limit is
1 MB — comfortable at a thousand, a wall somewhere in the tens of thousands.

⚠ And the count is memoised **per source**, so it is paid once per generation — but a *jump*
invalidates, which makes a new source, which recounts. A long scrub therefore costs a refresh plus a
recount before the rows appear. On the device that settle is a few seconds of placeholders. It is
the strongest argument for a real count op.

### 4.3 Still open

- **PH-17d** — the date bubble while dragging. Unstarted.
- **A terminal count op** (`core-keyset-paging.md` §11). Everything in §4.2 is waiting on it.
- ⚠ The scrub settle is unmeasured as a number; it was observed as "grey at ~2 s, filled by ~10 s".


## 7. As built — PH-17d, the date bubble (2026-09-01)

A label beside the thumb, while the thumb is held, naming the day of the row under it.
`FeedScrubber` takes `dateAt: (index: Int) -> String?`; the screen resolves it with
`items.peek(index)` and formats through the same `dayLabel` the grid's headers use.

### 7.1 ⚠⚠ The design question is what it does when it does not know

This phase's row said it is *"the piece that most obviously lies when the list is partial"*, and
after PH-17c the list is still partial in the way that matters: the **track** spans the library, the
**rows** do not. Through most of a fast drag the row under the thumb is a placeholder.

Two options, and they are not equally honest:

| | |
|---|---|
| hold the last known date | smooth — and shows a *different photograph's* date as though it were this one's |
| show nothing | flickers — and is true |

⚠ It shows nothing. `dateAt` answers `null` for an unloaded row and the bubble is simply not drawn.
The jump refresh fills it in within a beat, so in practice it settles rather than strobes; and when
it is quiet it is because the app genuinely does not know yet.

⚠ `peek`, not `get`: resolving a row to *label* it must not tell Paging the row was displayed, or
dragging the thumb would drive prefetching on its own. It is read in composition, so a page landing
recomposes the bubble — which is what lets it fill in without polling.

⚠ Only while dragging. At rest the grid's own day headers already say where you are, and a
permanent bubble covers the photographs it describes.

### 7.2 ⚠⚠ The mutation sweep reported the opposite of the truth, twice over

Three mutants, all three initially "SURVIVED" — and every one of those results was **a mutant that
failed to compile**. The harness read the previous run's JUnit XML and reported green tests as
survivors. ⚠ `if (false)` in place of `if (bubbleLabel != null)` does not compile: the null check is
what smart-casts the label for the `Text`.

The harness now asserts the results are **newer than the run that produced them** and that
compilation succeeded, and treats either failure as *inconclusive* rather than as a survivor. With
that in place: A (never shows) and B (holds a stale label) were killed by exactly the two tests that
name them — and **C (shows at rest as well) genuinely survived**, because nothing asserted the
bubble's absence at rest. That test now exists.

⚠ A mutation harness that cannot tell "nothing failed" from "nothing ran" inverts its own answer.
This is the second time in this track that a green result meant the opposite of what it looked like;
the first was `asSnapshot()` returning only the initial page (`core-keyset-paging.md` §23.1).

### 7.3 ⚠⚠ The device pass found the bubble naming a date that was nowhere on screen

Eight green tests, four killed mutants, and on the phone the bubble read **13 September 2021** while
the grid's top header read **8 August 2022**. It was not a flicker on the way somewhere: the finger
was held still and it was still wrong a full minute later.

The label and the seek were computed from the same `progress` but not from the same
`totalItemsCount`:

| | index used |
|---|---|
| the seek, in the drag handler | `progress × (count **when the drag event fired**)` |
| the label, in composition | `progress × (count **now**)` |

and the count moves. `insertSeparators` inserts a day header only between *loaded* rows, so every
page that lands adds headers and grows the list. A still finger sends no further drag events, so
nothing ever re-seeks, and the two indices stay apart for as long as the thumb is held.

⚠⚠ **The fix is to stop deriving the label from the gesture at all.** It now reads
`gridState.firstVisibleItemIndex` — the row the grid is actually showing. The disagreement becomes
unrepresentable rather than merely unlikely: the bubble and the header describe the same row by
construction, whatever the count does.

⚠ This is the *opposite* choice from the thumb's position, which deliberately follows the finger
rather than the list (reading the grid back there would make the thumb lag its own gesture). The two
are not inconsistent: the thumb is being driven by the finger, and the label is describing what the
user is reading. Whoever is driving owns the position; whatever is on screen owns the label.

⚠ What the eight tests could not see. Every one of them held the item count still, and with a still
count the old arithmetic agreed with the new — the fixture could not produce the divergence, so no
assertion could catch it. The regression test now grows the list under a held finger
(`theBubbleNamesTheRowOnScreen_evenWhenTheItemCountGrowsUnderAStillFinger`), and it is the only test
here that fails against the old implementation. ⚠ It grows at the *end*, which is not where a
separator lands; what it reproduces is the count changing under a still finger, and the invariant it
asserts — **the bubble names the row on screen** — is independent of where the growth happens.

⚠ A near-miss worth recording, because it looked identical at first: seeking to late 2020 showed the
bubble reading "26 October 2020" over a grid whose top header read "18 October 2020". That one was
**correct** — the 26 October rows were at the top of the viewport with their header scrolled just
above the fold. A screenshot cannot distinguish "the label disagrees with the visible header" from
"the label names the row whose header is off-screen", and only the first is a defect. The way to
tell them apart is whether the top row is flush with a header or cut off by it.


## 8. As built — PH-17c′, the year pills (2026-09-02)

Pills down the track while the thumb is held, one per year the library actually holds, spaced by
how many photographs each year holds rather than by the calendar.

### 8.1 ⚠⚠ The blocker in §5 was not real, and it cost this phase a month

The phase row said the pills wanted *"a per-year density map over the whole library"* and that *"the
only whole-library list is the shared index, which costs ~55 s cold"*. Both halves are true and the
conclusion does not follow. `feedYearMarks` took a `List<Photo>`, so getting a map meant getting
**every photograph** — and the only thing that holds every photograph is the index.

A density map reads exactly one column. `feedCaptureTimes()` is a `getAll` over the feed's own
window projecting `relations(CreatedAt)` and nothing else, and it is *cheaper than the count this
track already shipped*: `feedTotal` used to project one path per row (~100 KB over Binder), and a
capture time is eight bytes.

⚠ The generalisation from `List<Photo>` to `List<Long>` is the whole unblocking. PH-17a wrote the
grouping against the type it happened to have rather than the type it needed, and the doc then
recorded the consequence as a property of the problem.

⚠⚠ Whether a terminal accepts a projection with **no `fields(PATH)`** was the one genuine risk, and
`feedRowProjection` carries a comment saying PATH "is not optional" — for the *streaming* terminal,
which derives a row's DataId from it. `getAll` does not, but no mock can tell you that:
`theDensityMapReadsEveryCaptureTimeNewestFirst` runs it through the real in-process stack.

### 8.2 ⚠⚠ The pills are placed in PHOTOGRAPH space, and the alternative is worse

The track is drawn over the grid, and the grid holds a full-span day header before each new day — so
"where is 2021 on this track" has two candidate answers, and the obvious one is wrong:

| scale | a year's offset | why not |
|---|---|---|
| **photographs** (chosen) | `photographsAbove / photographs` | agrees with the seek; off only by *variation* in days-per-photograph |
| fully-grouped grid items | `(photographsAbove + headersAbove) / (photographs + days)` | exact on a fully-loaded grid — which never exists |
| live grid items | — | not computable: nothing knows how many headers sit above a placeholder |

The trap is that the middle row *looks* the most accurate. It is not, because the **drag** seeks with
`fraction × liveGridItems`, and the live item count carries headers only for the pages Paging holds
right now. `insertSeparators` inserts between *loaded* rows, so the live count starts near the
photograph count and grows towards the fully-grouped one as the user scrolls. Placing pills against
the fully-grouped total would make them disagree with the seek by every header **not yet loaded** —
a larger error than the density deviation, and one that changes under the user's finger.

⚠ Photograph space is the only scale on which the pill and the seek agree, and its residual error is
second-order: exactly zero where days-per-photograph is uniform, and growing only with the
*deviation* from that. ⚠ Unmeasured on a real library — the number worth having is the spread of
days-per-photograph across the user's years, and this is the honest place to say it has not been
taken.

⚠ It also means `dayCount` — briefly added to `FeedYearMark` and reverted — buys nothing. Counting
headers into the offsets is precision against the wrong denominator.

### 8.3 Thinning, and which pill survives

Density spacing guarantees crowding: most people's photographs are recent, so the thin years bunch
at the bottom — on Google Photos, 2023, 2019 and 2017 sit within a thumb's width. `thinToFit` drops
a pill closer than one pill-height to the one above it.

⚠ Greedy **from the top**, so the newest of a crowded run survives. The fractions ascend, so keeping
the first of a cluster leaves the label at the boundary the user is dragging *towards* — it names
the next year they will reach rather than one they have passed. Mutant E flips this and dies.

### 8.4 ⚠⚠ A fixture that cannot tell a density track from a calendar

`aMarkSitsAtItsShareOfThePhotographsAboveIt` was written with five photographs in 2026 and one in
2024, and it **passed against a calendar track** — because with two marks (or with three of equal
size) evenly spacing the years gives the same numbers as spacing them by density. The fixture could
not represent the difference the feature exists to make.

The mutation sweep caught it, and only by luck: mutant D was killed by a *UI* test whose fixture
happened to be uneven. The logic test now uses three years of 8/1/1 and asserts the thin years sit
near the bottom rather than at a third and two-thirds.

⚠ Same shape as PH-17d's regression one day earlier (§7.3): a suite is blind to whatever every
fixture holds constant, and killing the mutants tells you the tests can see the *code*, not that the
fixture can produce the *condition*.

### 8.5 ⚠⚠ Rendering the control found a defect no assertion was going to

Twelve green tests, five killed mutants — and a PNG of the scrubber over a realistic library showed
**"2025" ghosting through "13 September 2021"**. PH-17d's date bubble is `surfaceVariant` at
`alpha = 0.92`, to match the thumb, so a pill behind it does not disappear: it bleeds through the
label, right where the user is looking.

No test was going to catch that. Every assertion available asks *does this node exist* — and both
nodes existing is precisely the bug. Overlap is a question about pixels.

⚠ The fix is `hideUnderThumb`, applied **after** thinning: thinning first and hiding second leaves a
clean gap, while hiding first lets thinning promote the next pill down into the same covered spot —
the same collision one row later. The rule is also the right one independent of the transparency:
the thumb is where the user already is, and the bubble names that position exactly, so a pill there
says less and says it worse.

⚠ How to reproduce the picture: a `runDesktopComposeUiTest` that drives the drag and then
`onNodeWithTag(FEED_GRID_TAG).captureToImage().toAwtImage()` into a PNG. It was deliberately **not**
committed — a `@Test` with no assertions is a test that cannot fail, and this suite already learned
what a green result that means nothing costs (§7.2).

⚠⚠ It also broke a test in a way worth recording: `theYearPillsAppearWhileTheThumbIsHeld` asserted
three pills, and the new rule hid one — because the fixture's years were spread evenly and the grab
left the thumb next to one of them. The count depended on where the *grid* happened to be scrolled.
Fixed by putting the years in the top half and dragging the thumb to the bottom, where `progress`
clamps to 1 and the thumb's position is a known quantity.

### 8.6 What it costs

One `getAll` per view model, on first subscription to `yearMarks`, under
`WhileSubscribed(5 s)` — so a feed the user never scrubs never pays for it, and re-entering the
screen does not re-read.

⚠ It deliberately does **not** refresh when the feed changes. A photograph arriving moves every pill
below it by one row in hundreds, which no eye resolves, and re-reading the library on every sync
tick would cost far more than the staleness.


### 8.7 Device pass — OnePlus CPH2581, 922 photographs spanning 2018–2026

| what | result |
|---|---|
| pills while dragging | eight marks, **density-spaced**: 2026 and 2025 far apart at the top, 2023/2022/2020/2019/2018 bunched at the bottom — the shape §1 measured on Google Photos |
| the pill under the thumb | **hidden**, a clean gap where 2024's pill would otherwise ghost through "21 December 2024" |
| thinning | **2021 dropped** between 2022 and 2020, which sit a little over one pill-height apart |
| **does a pill tell the truth?** | thumb parked just below the **2020** pill landed on **20 November 2020** — six weeks into the year whose start that pill marks |
| at rest | no pills, no bubble, thumb fading, previews decoded |

⚠⚠ The fourth row is the one that matters, because it is the only one that tests the *claim*. Pills
that are merely drawn in the right visual rhythm would pass every other row here; a pill is only
worth having if dragging to it arrives where it says.

⚠ The residual error §8.2 predicts — the deviation in days-per-photograph — was not separately
measurable at this library's size: landing six weeks into a year is within the width of the pill
itself. A library with a very uneven day distribution (one year of daily single shots against a year
of holidays) is where it would show, and this one is not that.

⚠ On this library thinning removed exactly one pill. A denser back catalogue would drop more, and
whether the survivors still read as a *map* rather than as an arbitrary subset is a judgement only a
bigger library can settle.
