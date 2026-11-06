# Keyset paging for `:core-paging`

**Status**: v1.0 — **K-1…K-4 all built** 2026-09-01. The track is complete; §11 is what it left open.
**Motivating case**: Photos' feed, which cannot use `.pagingSource()` today; and the fast-scroll
scrubber (`photos-feed-scrubber.md`), which needs a track over the whole library.

---

## 1. The question this exists to answer

*"Why doesn't Photos use `.pagingSource()` like Drive does — is a feature missing?"*

Yes, and it is in `:core-paging` rather than in Photos. Today the answer lives in one KDoc on
`TenshuPhotosRepository.feedPage`, which is not where anyone will look for it.

## 2. What is actually missing

`TenshuPagingSource` is **`PagingSource<Int, R>`** (`PagingUtil.kt`), and its key is an offset:

```kotlin
val position = (params.key ?: 0).coerceAtLeast(0)
```

Offset paging is correct only while the source is **stable between page reads**. When rows are
inserted ahead of the cursor, page *n+1* re-serves a row from page *n* or skips one, because the
offset that addressed row 40 now addresses row 39.

⚠⚠ **For Photos that is not an edge case, it is the normal case.** The feed is the whole realm
filtered to images, and the backup drain is inserting into it *while the user scrolls*. Observed
directly on 2026-08-31: a photograph was taken on the device and appeared in the feed within the
time it took to switch back to the app.

So Photos hand-rolls a **keyset** pager instead, and `feedPage`'s KDoc says why:

> *"Keyset, not offset, and the difference is not stylistic: the realm gains photos while the user
> scrolls (the backup drain is running), and an offset page would then serve a row twice or skip
> one. A cursor on capture time cannot."*

The cursor is expressed in the chain itself — `filter { createdAt.inRange(0L, beforeMs) }` — so the
capability exists at the query layer. What is missing is a paging source that can carry it.

## 3. ⚠ What is *not* the reason

**Performance is not the blocker, and assuming it was is the easy mistake.** The intuition is that
an offset window would lose `core-ordered-window-fusion.md`'s F2 fusion and turn every page into a
whole-realm sort. It would not: the fused path folds a `drop`/`take` **prefix** into

```
winners = boundedStableTopN(rows, comparator, offset + limit)
result  = winners.drop(offset)
```

with a resident set of `O(offset + limit)`, not `O(N)` (§F2). Deep paging costs more heap as the
offset grows, which is the ordinary offset-paging trade-off, but it does not degrade to a scan.

⚠ The correctness problem is the whole of it. Recording this because the plausible-but-wrong reason
is the one a reader reaches for first.

⚠⚠ **And the intuition is wrong twice over.** Building K-1 turned up that `TenshuPagingSource`'s own
KDoc asserted the same false thing in the other direction — that `sortBy(key).drop(pos).take(n)`
"misses the gate entirely", so an ordered source must cut its window as `take(pos + n).drop(pos)`.
`tryOrderedWindowFusion` has accepted a **leading `Drop`** since ordered-window fusion F1
introduced it, and `foldWindowPrefix` folds either order to the same `(offset, limit)`;
`OrderedWindowFusionTest.dropTakeWindow_slicesTheOrderedList` executes the supposedly-unfusable
shape and asserts sort, drop and take are all consumed. Not a rule that decayed — wrong when
written, and it had a test next to it whose *rationale* repeated the claim while its assertion only
pinned a shape.

## 4. What Photos pays for the workaround

- **No `PagingData`**, so no `cachedIn`, and none of `core-paging-scroll-restoration.md`'s work
  applies. Photos keeps its rows in the view model instead, which is why its scroll-restoration bug
  was the *mirror image* of Drive's — see the tech-debt entry on Photos' navigation.
- **A second pager to maintain**: `cursorMs`, `inFlight`, `endReached` and the append/replace fold
  are hand-written in `PhotosFeedViewModel`, duplicating what Paging 3 does.
- **A merge on refresh** (`refreshNewest`) that Paging's invalidation would have handled.

## 5. Sketch

`PagingSource<K, R>` where `K` is the cursor type, and the caller supplies the two halves the chain
already understands:

```kotlin
fun <T : Data, R : Any, K : Any> TList<T>.keysetPagingSource(
    mapper: (T) -> R?,
    /** The chain filter that opens the window below a cursor. */
    window: (TList<T>, K?) -> TList<T>,
    /** The cursor of the last row of a page, for the next call. */
    cursorOf: (R) -> K,
    pagingConfig: PagingConfig = defaultPagingConfig(),
): Flow<PagingData<R>>
```

Photos' feed becomes `window = { list, before -> list.filter { createdAt.inRange(0L, before ?: MAX) } }`
and `cursorOf = Photo::capturedAtMs`.

⚠ **`getRefreshKey` is the interesting half.** With an offset source it returns an `Int` near the
anchor; with a keyset one it must return the *cursor* of a row near the anchor, which is what makes
a reload re-anchor rather than jump. `core-paging-scroll-restoration.md` §2 notes that
`getRefreshKey` only became live once `cachedIn` gave it anchor state to read — the same applies
here and should be tested, not assumed.

## 6. Open questions

1. **Does the per-change `Pager` rebuild still make sense?** `pagingSource` currently rebuilds the
   whole `Pager` per settled change via `flatMapLatest` (the Android folder-listing-staleness fix).
   A keyset source is *insert-tolerant by construction*, so it may not need the rebuild — which
   would also retire the `INVALIDATION_COOLDOWN_MS` dance for these callers.
2. **Do Drive and Fit want this too?** Both are offset today and both read realms that can grow.
   Drive's exposure is smaller (a folder, not the whole realm) but `drive-folder-listing-consistency.md`
   exists because it is not zero.
3. ~~**Is `orderedSource` still worth a parameter?**~~ **Retired 2026-09-15**
   (`drive-sorted-folder-paging.md` SP-3). Its two windows were equivalent (§3), so the ordered
   branch was a reshuffle, not an optimisation; every source now cuts `drop(pos).take(n)`, and a
   sorted source says so through `ordering` — the chain applied ahead of the window — which is the
   parameter that actually distinguishes one. Fit's call lost the flag; nothing else changed there.
4. ~~**Duplicate cursors.**~~ **FIXED** — it was a defect, not a question, and it had three sites:
   `PhotosFeedViewModel`, plus two `while (true)` cursor loops (`collectFeedThePreIndexWay`,
   `findByContentId`) where a page sharing one millisecond did not merely duplicate but **failed to
   terminate**. ⚠ An exclusive bound is not the fix — it loses every row tied at a boundary. The
   cursor is now a `(time, skip)` pair, `skip` being how many held rows share the boundary time,
   pushed into the chain as `.drop(skip).take(n)` which F2 folds into the same bounded top-N.
   ⚠⚠ `MockPhotosRepository` used `<` while production used `inRange` (`<=`), so no mock-backed
   test could see it — and `theMockRepositoryPagesLikeTheRealOne`, the guard written to catch
   exactly that divergence, was asserting the mock's side of it.

## 7. ⭐ Scheduled — the scrubber needs it (2026-09-01)

This was written as "not scheduled, nothing depends on this". Something does now.

`photos-feed-scrubber.md` §3 asked whether the Photos feed should render from `RealmContentIndex`
instead of paging, so that a fast-scroll thumb could address the whole library. Checking Paging 3
rather than assuming showed the two answers **converge**, and that this one is the better road:

- **Paging already prepends** — `TenshuPagingSource` returns a non-null `prevKey` unless at position
  0. ⚠⚠ The one-directional wall that made "re-anchor the cursor" unacceptable is a limitation of
  Photos' *hand-rolled* pager, not of keyset paging. That objection does not apply here.
- **`jumpingSupported` is never set** (Paging defaults it `false`). Without it a scrub across years
  loads every intervening page; with it, Paging invalidates and refreshes at the anchor through
  `getRefreshKey` — which §5 already flagged as the interesting half.
- **`enablePlaceholders = false`** today. With placeholders on and a total — which `ContentIndex`
  already holds — `LazyPagingItems.itemCount` is the whole library, so the scrubber's track is
  full-length without the feed holding every row.

⚠ So the work is larger than §5's sketch: a keyset `PagingSource` is necessary but not sufficient
for the scrubber. It also owes `jumpingSupported`, a `getRefreshKey` that returns a **cursor** near
the anchor rather than an offset, and a placeholder-capable config with a total.

⚠⚠ And `prevKey` is the part §5 under-described. An append-only cursor is easy; **prepend needs a
window that runs the other way** — rows *newer* than the cursor, taken from the near side and
reversed — which is a second chain shape, not a sign flip on the first.

### 7.1 Phases

| | | |
|---|---|---|
| **K-1** | ✅ 2026-09-01 | `TenshuKeysetPagingSource` + `toKeysetPagingData` in `:core-paging`, append-only, with the tie skip and a re-anchoring `getRefreshKey`. As-built §8. |
| **K-2** | ✅ 2026-09-01 | Prepend, and with it a real `prevKey`. As-built §9. |
| **K-3** | ✅ 2026-09-01 | `jumpingSupported` + placeholders over a total — the scrubber's actual requirement. ⚠⚠ **Could not ship without K-2**, see §7.2. |
| **K-4** | ✅ 2026-09-01 | Photos' feed onto it — `cursorMs`/`cursorSkip`/`inFlight`/`endReached`/`refreshNewest` all deleted. As-built §10. |

### 7.2 ⚠⚠ K-2 and K-3 do not separate — the phase split was wrong

They shipped together because K-3 alone is broken, and that is legible in Paging's own source rather
than a matter of taste. `PageFetcherSnapshotState` sets `placeholdersBefore = page.itemsBefore` on a
REFRESH, and a PREPEND is the only thing that reduces it. So a jump to rank 12,000 with no prepend
leaves one of two states, both wrong:

- prepend returns `itemsBefore = 0` → the 12,000 placeholders vanish and the list collapses under
  the reader;
- prepend returns `COUNT_UNDEFINED` with a null `prevKey` → 12,000 grey tiles above the user, for
  good, with nothing that will ever fill them.

⚠ The split in §7.1 was written before that was checked. Recording it because "K-3 needs
placeholders, K-2 needs a reverse window, so they are independent" is a plausible reading of the two
phases as I wrote them — and it is wrong.

## 8. As-built — K-1

`core-paging/.../KeysetPagingSource.kt`, ten tests in `KeysetPagingSourceTest`.

- **The key is `KeysetKey(cursor, skip)`**, the `(time, skip)` pair §6.3 arrived at, now a type
  rather than two parallel variables per call site.
- **`cursorOf` reads the projected *source* row, not the mapped one.** ⚠ A partial `mapper` can
  empty a whole page, and a cursor taken from mapped rows would then have nothing to advance from —
  the pager stalls on one window forever. Pinned by `aPageTheMapperEmpties_stillAdvancesTheCursor`.
- **The skip carries across pages.** A tie run spanning a page boundary needs the count of rows at
  that value served by *earlier* pages too, not just this page's.
- **`getRefreshKey` reads the previous page's `nextKey`**, not the anchor page's `prevKey`. ⚠
  `prevKey` means "the page before this one" in Paging's contract; overloading it as a refresh
  anchor would hand K-2's prepend a key pointing at the page it is trying to precede. Costs nothing
  and keeps the two meanings apart.
- ~~**Prepend is served as an empty page with a null `prevKey`**~~ — K-1 shipped append-only, and
  `aPrependIsServedEmpty_untilTheReverseWindowExists` was written to go red when K-2 landed. ✅ It
  did, the same day; §9 replaced it.

⚠⚠ **The fixture really slices**, unlike `TenshuPagingSourceTest`'s `MockTList` whose `drop`/`take`
record the window and return the receiver unchanged. Every defect these tests exist to catch is
invisible to a fixture that returns the same rows whatever window you ask for.

⚠ **The two tie tests drain the pager under a bounded loop** rather than asserting a fixed page
count, because "does it terminate" is half of what they check and a broken skip does not fail an
assertion — it never stops. Verified by mutation, not by reading: removing the skip entirely fails 7
of 10; removing only the *carry* fails exactly the two tie tests, both with "the pager did not
terminate after 50 appends"; making `getRefreshKey` always return null fails exactly the
re-anchoring test.

⚠ `anInsertAheadOfTheCursor_shiftsTheOffsetPagerButNotTheKeysetOne` runs **both** sources over the
same fixture and the same insert, so §2's premise is demonstrated rather than asserted: the offset
pager's page 2 opens on the row page 1 ended with.

## 9. As-built — K-2 + K-3

⚠⚠ **Only the forward read is keyset. Both seeks are positional.** A prepend and a scrubber jump
open the window at its head and drop to where they need to be, and `KeysetKey` gained a `position`
to carry that. This is the load-bearing decision of the phase, so here is the reasoning rather than
the conclusion:

- **A rank is not expressible as a cursor.** "The row 12,000 rows down" is exactly what an offset
  is. There is no key that means it.
- **A reverse cursor window does not work for prepend either**, which is what §7's own sketch
  proposed. Take a boundary value `C` with several rows tied at it, some loaded, some not. The
  reverse window (`key ≥ C`, ascending) enumerates ties in *encounter order* — the same order the
  descending feed does — so the unloaded rows at `C` come **before** the loaded ones in that window.
  `drop(skip)` from its head removes the wrong ones. A strict `>` bound loses them instead. The rank
  has no such problem: the page above `[p]` is `[p − n, p)`.
- **What it costs**, stated rather than hidden: a seek can drift by however many rows were inserted
  since the page it seeks against was read. An insert *causes* an invalidation, so it resolves on
  the next reload; the exposure is one page for one debounce window. The forward read — where the
  user spends essentially all their time — never drifts, which is the property §2 exists for.
- **A seek costs one `O(position)` read** (`sort.drop(p).take(n)`, F2-fused to a bounded top-(p+n)
  of thin rows). Continuing from there is `O(pageSize)` per page again.

⚠⚠ **A positional page cannot always hand back a cursor**, and this is the subtlest thing in the
phase. Its window opened at the head and dropped, so rows tied with the page's last row may sit
*above* where it started — invisible from the page, and a skip that missed them re-serves them on
the next page. The tell is cheap and exact: if the page's first row's key differs from its last, the
boundary's tie run demonstrably began inside the page. If the whole page is one tie run it stays
positional rather than guessing — which is also the case where a cursor buys nothing, since you
cannot step through a run with a value that never changes.

⚠ **The total is counted once per source, not once per page.** For Photos it is a `count()` over a
*filtered* whole-realm traversal, which has no O(1) member-hash count to fall back on and walks the
tree — per page that roughly doubles what a page costs, to keep a placeholder region exact to the
row. A source *is* one generation (the factory rebuilds the `Pager` per settled change), so a row
arriving means a fresh count anyway; the window a count can be wrong in is the invalidation
debounce. ⚠ This reverses what the first draft's KDoc argued, which was written before anyone
looked at what the only real caller would actually pass.

⚠ `seekablePagingConfig()` exists because placeholders, `jumpThreshold` and `totalCount` are one
decision. `PageFetcherSnapshot` throws if a config sets `jumpThreshold` against a source that does
not declare `jumpingSupported`, and Paging *ignores* `itemsBefore`/`itemsAfter` when placeholders
are off — so configured apart, the failure is either a crash or a listing that reports a full-length
track and silently is not one. The factory `require`s the pairing rather than letting it be inert.

**Verified by mutation, eight mutants, no survivors** — including the two that only a wrong-shaped
test would miss: a positional page always handing back a cursor (killed only by
`aJumpLandingInsideATieRun_...`), and `jumpingSupported` declared without a total.

⚠⚠ **The sweep also caught a test that had stopped testing what it was named for.**
`aTieRunLongerThanAPage_...` used a fixture that was entirely one tie run, which after K-2 sends the
source down the *positional* fallback — so it passed with the tie skip deleted. It now begins with
five distinct rows so the run is reached through a cursor, and the all-tied case is its own test for
the fallback. An assertion that survives the mutation it was written for is worse than no assertion,
because it reads as coverage.


## 10. As-built — K-4, Photos' feed

`PhotosFeedViewModel` went from ~200 lines of pager to a `Flow<PagingData<FeedItem>>`. What was
deleted: `cursorMs`, `cursorSkip`, `inFlight`, `endReached`, `advanceCursor` (which counted boundary
ties off the accumulated list), `refreshNewest` (a merge of a fresh head page into the held tail),
and the screen's `derivedStateOf` "near the end" listener with its threshold constant.

⚠ **`feedPage` stays.** Only the read with a *scroll position* moved — the Places grouping and the
duplicate scan still page the library themselves. Both now share one `feedWindow` chain rather than
spelling the window twice, because the two diverging is not hypothetical (§6.4).

### 10.1 Three things the shape forced

- **Day headers became a separator rule.** The screen ran `photos.groupIntoDays(dayStartOf)` over
  every held row on every change, which is what a `List<Photo>` forces. A paged grid addresses a
  flat index, so the item at *n* must be renderable without the rows after it — the grouping is now
  `insertSeparators`, and a `FeedItem` is a tile *or* a header. ⚠ The property the whole-list pass
  guaranteed survives: `before` is the previous page's last row, so a day straddling a seam still
  gets one header.
- **The failure classification had to ride an exception.** `PhotoPageResult` is
  `Page | Unreachable | Failed`; Paging's is `Page | Error`. Three states into two, and the
  distinction cannot be dropped — "Control is not running" and "your library is empty" look
  identical on screen and only one is true. Hence `FeedReadException(unreachable)`, read off
  `LoadState.Error.error` and **never** off its message text.
- **⚠⚠ One presenter, hoisted above the nav host.** The viewer swipes through the *feed's*
  photographs and extends them, so a presenter of its own would start at the first page and could
  not address a photograph the grid had scrolled to. Both routes share one `LazyPagingItems`; the
  viewer filters the tiles out of it. Asking for the last loaded row *is* the request for another
  page — `LazyPagingItems.get` sends the access hint — so `onLoadMore` survives as a read, not a
  call.

### 10.2 ⚠⚠ What the mock got wrong, again, in a new way

`PagingData.from(data)` passes `sourceLoadStates = null`, which leaves the presenter on its
**initial** states — `refresh` stays `Loading` forever. A screen that says "No photos yet" only once
a refresh has *finished* then never says it, and against a mock library with rows in it nothing
looks wrong, because rows render whatever the load state claims. The empty state would have been the
one screen this mock could not exercise. It now passes explicit settled states.

⚠ Same family as §6.3's `<` vs `inRange`: a mock that disagrees with production at a boundary hides
exactly the bugs that live there. What is different is that the mock no longer *reimplements* the
window — the arithmetic is `:core-paging`'s and is tested against a fixture that really slices, so
there is nothing left for a second copy to get wrong.

### 10.3 Coverage moved, and one piece was lost

`PhotosFeedViewModelTest` went from thirteen tests to four, and the file says where each went: the
cursor, tie and short-page tests are `KeysetPagingSourceTest` (verified by mutation); the three
error/empty screens are `PhotosFeedScreenTest`, which pins pixels rather than a flag; the in-flight
guard has nothing left to test. What remains is the one paging-shaped decision that is Photos' own —
where the day headers go.

⚠⚠ **`refreshNewest` had three tests and its replacement has none.** The replacement is the chain's
invalidation, which this module cannot exercise: `MockPhotosRepository` serves a static list and the
real signal needs a store.

⭐ **Verified on a device 2026-09-01**, by accident and then on purpose: restoring a photograph from
the Trash puts it back in the feed with no explicit refresh anywhere on that path, the presenter
hoisted above the nav host so returning to the tab does not rebuild it, and the process unrestarted.
The reload can only have come from the change signal. ⚠ Still no *test* — that needs a store, and
`RealRepositoryReadTest` is now the fixture that could grow one.

Six mutants on the K-4 logic, no survivors: a header before every photograph; a trailing header; no
header at the head; a mock load that never finishes; an error on an empty feed falling through to
the grid; an unreachable proxy reading as a generic failure.

## 11. What the track left open

- ~~**Placeholders are available and not turned on.**~~ ✅ Turned on by `photos-feed-scrubber.md`
  PH-17c (2026-09-01), verified on a device.
- ~~**There is no terminal count operation.**~~ ✅ **Fixed 2026-09-01 — `resolve()`**, and the fix
  was not a count op. See §12.
- ~~⚠ The total still costs on every *jump*: a jump invalidates, and a new source recounts.~~ ✅ **Fixed 2026-09-03 — §13.**
- **Drive and Fit are still offset** (§6.2). Both read realms that can grow.
- ~~**`orderedSource` is a parameter that no longer distinguishes anything** (§6.4).~~ ✅ Retired
  2026-09-15; `ordering` replaced it (§6.3).
- **A string key cannot be a keyset cursor** (2026-10-02). The cursor here is `inRange`, which is
  integers only, so a walk ordered by path or name is still offset-paged. The follow-up —
  `greaterThan` / `lessThan` — and what its absence costs Duplicates and the backup skip are in
  [`core-ordered-window-fusion.md`](core-ordered-window-fusion.md) §9.7. Not scheduled.


## 12. `resolve()` — the value terminal (2026-09-01)

`count()` had existed, been implemented, and carried a proxy-side short-circuit the whole time. What
it lacked was **a way for a client to ask for the answer**, and the shape of that gap was not
obvious from the failure.

### 12.1 Why it looked like "count cannot be executed remotely"

Dispatch is chosen by the **terminal operation's traits** (`DefaultInteractorProvider.getInteractor`):
stream → `StreamInteractor`, IO → `TaskInteractor`, background → cancellable, and **everything else
falls through to a synchronous interactor**. `Count` carries no traits — deliberately, because its
one caller is `Folder.FileCount`, a proxy-side dynamic property whose non-suspend resolver could not
await a `TaskInteractor`. So a chain ending in `count()` was routed to `TaskExecutor.execute`, which
on a client is `RemoteBinaryTaskExecutor.execute`: it walks the callstack running each op through
`PureOperationRunner` and throws `SynchronousViolationException` at the first non-pure one, which is
`traverse`.

⚠⚠ **The op was fine. The chain had nowhere to end.** Re-marking `Count` with IO traits — the first
fix considered — would have broken `Folder.FileCount`, because a dynamic-property resolver cannot
await. The missing piece was a terminal for *values*, sitting beside `GetAll` (lists) and `Get`
(`Maybe<T>`).

### 12.2 ⚠⚠ It cannot be called `get()`

`TInteger` declares a **member** `fun get(): Long`, and in Kotlin a member always beats an
extension. An extension named `get()` would silently bind to the local accessor on exactly the type
this exists for, and fail at runtime with `IllegalStateException: Not a data object` — which is the
error that made this look like a dispatch problem in the first place. `Maybe.get()` escapes it only
because `Maybe` has no local `get()`.

⚠ The asymmetry — `get` for `Maybe`, `resolve` for values — is worth reconciling. Filed, not done:
renaming `Maybe.get()` touches every call site and buys consistency rather than correctness.

### 12.3 Shape

- Kotlin receiver is **`TValue`**, not `Data`: terminating a composite is `Get`'s or `GetAll`'s job,
  and a second way to do it would be a way to get it wrong. The *schema* receiver is `Data`, because
  the schema layer has no "any value" type — `EqualTo` and `InRange` are declared the same way.
- The proxy-side implementation is a **pass-through**. It looks like a no-op because it is one: the
  ops before it did the work, and this exists so the chain has a terminal carrying `IoOperation`.
  The value of the operation is in its traits, not its body.
- **Callable, not cheap.** `count()` only reaches the raw-array short-circuit on an *unfiltered*
  array; past a `filter` it materialises and counts proxy-side.

### 12.4 Measured on a real library (~900 photographs)

| | cold | warm |
|---|---|---|
| `getAll` + path projection, counted client-side | 8,919 ms | 1,031 ms |
| `resolve()` | 1,015 ms | **698 ms** |

Both answer 922. The traversal is the same work; what changes is the wire — one integer instead of
one path per photograph, which removes the 1 MB Binder ceiling that made the old approach a dead end
at scale.

### 12.5 ⚠⚠ A new core op needs the PROXY rebuilt, and skew is silent

The first device reading was `null` in ~16 ms, with every unit test green — because the tests run
in-process over `LocalRequestChain` and never touch Binder. Photos had the op; **Control, which is
the proxy, was still the master build and did not.** The call was rejected instantly and Photos'
`catch` turned it into `null`, which `feedTotal` documents as "no placeholders this generation".

⚠ The degradation is correct and the silence is the problem: it does not look like version skew, it
looks like a slow or failed read. `TenshuPhotosRepository.feedTotal` now says so where someone
measuring will actually read it.

### 12.6 Schema

One new type, `tenshu/core/value/resolve`. `SchemaShapeBaselineEnforcementTest` named it and the
regenerated diff is **one added line with nothing moved** — the review signal the test asks for. No
composite reshaped, so no `SchemaMigration` and no `TENSHU_STORAGE_FORMAT_VERSION` bump (that
constant wipes every store; adding an operation is not a reason to). ⚠ `property-kind-baseline.txt`
pins *properties* and is correctly untouched.


## 13. The count is a property of the list, not of a paging generation (2026-09-03)

§11 carried *"the total still costs on every jump: a jump invalidates, and a new source recounts"*.
On Photos' feed that is a full-tree count — ~700 ms on 922 photographs — paid **on top of** the
refresh, before any row appears. It is most of the grey that follows a fast scrub, and PH-17c′ made
scrubbing the normal way to move through the library.

`TenshuKeysetPagingSource` memoises the total on itself, which is correct for one source serving
many pages. The bug is the scope: Paging builds a **new source every time it invalidates**, and a
jump invalidates — so the memo was discarded exactly when the reader moved.

⚠⚠ **A jump does not change the data; it changes where the reader is looking.** The count is a
property of the list, so the memo belongs to the thing that tracks the list changing —
`reloadSignals()` — and not to the thing that tracks the reader moving. One line in
`keysetPagingSource`: the memo is built inside `flatMapLatest`, so every source of one generation
shares it and a real change still recounts.

⚠ A failed count is **not** memoised. `null` means the read failed — a dead proxy, a version-skewed
one — and caching it would disable placeholders for the whole generation on one bad read, which is a
scrubber track that collapses and never recovers. A value is remembered; a failure is retried.

### 13.1 ⚠⚠ The test passed for the wrong reason, and a mutation check is the only thing that said so

The first version flung the pager with `asSnapshot { scrollTo(35) }` and asserted the counter ran
once. It passed — **and it passed against the unfixed code too**, because `scrollTo` appends its way
to the index one page at a time. No jump, no second source, nothing for the memo to matter to.

`flingTo` is the primitive that produces a jump. The tell is in the returned window: a sequential
walk to index 35 returns ~36 rows, a jump returns a handful (5 here). Both are now asserted, because
the count assertion is worthless without a witness that the source was actually rebuilt.

⚠ This is the third time in this track that a green test meant nothing until a mutant was tried:
`asSnapshot()` returning only the initial page (§23.1), a density fixture that could not tell a
density track from a calendar (`photos-feed-scrubber.md` §8.4), and this.

### 13.2 Where it is pinned, and why not here

`aJumpDoesNotRecountTheLibrary` lives in **`photos-shared-logic`**'s `RealRepositoryReadTest`, not in
`:core-paging`. The paging module's fixture cannot serve `stream/start`, so `reloadSignals()` — and
therefore the whole flow — will not run against it; the first attempt failed with *"Operation
tenshu/core/collection/list/stream/start not found"*. The real in-process stack can, which is the
same reason PH-5's trash bug was only ever visible there. ⚠ Since `photos-app.md` §38 the module has
one fixture that does serve it — `FirstLoadWaitsForTheObserverTest`'s, which holds the subscribe
confirmation back the way a proxy across Binder does — and that is where *when* the first page is
read is pinned.

⚠ `:core-paging` keeps the memo's own semantics (`theCountMemoAnswersOnceAndKeepsTheAnswer`,
`aFailedCountIsRetriedRatherThanRemembered`), which is what a unit can honestly say about it.
