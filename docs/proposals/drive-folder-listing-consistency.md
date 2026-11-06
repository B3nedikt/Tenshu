# Drive folder-listing consistency — stale/incomplete reactive listing

**Status:** ROOT CAUSE PINNED + FIXED (2026-07-10). The bug is **client-side
Compose Paging reactivity**, NOT the proxy — specifically **`cachedIn` dropping
the refresh `load()` on new `PagingData` generations**. Two-part fix:
(1) `TList.pagingSource` rebuilds a fresh `Pager` per debounced change via
`flatMapLatest` (`core-paging/.../PagingUtil.kt`); (2) `FilesScreenViewModel`
drops `cachedIn` so those reactive reloads are not swallowed. Verified on-device
for single, spaced, AND rapid creates (§4).

> ⚠⚠ **Part (2) was UNDONE on 2026-08-12 — `cachedIn` is back, deliberately.** The finding below
> was real and correctly diagnosed. It has since **stopped being true**: SR-1 of
> [`core-paging-scroll-restoration.md`](core-paging-scroll-restoration.md) put the swallow under
> test on paging 3.5.0 and could not reproduce it — neither in an isolated fixture
> (`CachedInRefreshContractTest`) nor against this very pipeline through the real
> `LazyPagingItems` differ (`FilesPagingReactivityUiTest`). Part (1), the `flatMapLatest` rebuild
> in `TList.pagingSource`, is **unchanged and still load-bearing**.
>
> ⭐ It decayed unnoticed because its only evidence was a device session and this paragraph: the
> desktop twin was `@Ignore`d and *hanging* when this was written, going green only with the one-shot page-window read —
> afterwards. Both directions are permanent tests now, which is the actual fix for that. If
> `FilesPagingReactivityUiTest` ever goes red the ban is live again, and
> `core-paging-scroll-restoration.md` §7 holds the design that routes around it.
>
> Everything below is kept as written: the diagnosis of the *original* bug is still the reference
> for how client-side Paging reactivity fails, and §4's on-device method is still the method.

> ⚠ **This note previously concluded "it is proxy-side" — that was WRONG.**
> The 2026-07-10 on-device instrumentation (below) proves the proxy computes the
> correct window on every read; the failure is that the client Paging pipeline
> stops issuing / drops reactive refresh loads, so the visible page keeps
> rendering an *earlier* (correct-at-the-time) proxy result. The earlier
> "parent=2, detail=1, both proxy-computed" reading was a misinterpretation: the
> detail "1" was a stale earlier `load()` the client never refreshed, not a wrong
> value the proxy returned when asked. Do not re-open the proxy angle.

Related: the shipped one-shot `getAll` page-window read (orthogonal — cured the
initial-load-over-Binder facet); the invalidate-swallow guard (superseded by this fix, see §5).

---

## 1. Symptom

Android (Drive ↔ Control over Binder): a folder's **detail listing is
stale/incomplete** while the **parent's "N files" count is correct**. Repro:
creating children while viewing a folder, some don't appear; the parent count
stays right; re-entry always shows everything. Timing-dependent — a single or
well-spaced create appears; rapid successive creates leave the last one(s)
invisible until re-entry.

## 2. Proven root cause — CLIENT-SIDE Compose Paging reactivity

Two-sided emulator instrumentation (2026-07-10), tagged `PAGINGDBG2` across the
Control (proxy) PID and the Drive (client) PID:

- **The proxy is always correct.** For folder `.files`, the write path's array id
  (`EntityFieldDataId(StringDataId(File.ID, parentPath), FILES)`) and the detail
  read path's array id are **byte-identical** on every read (same `arrayIdHash`).
  A windowed read at `offset=0` **always** returns the current committed count.
  No entity-identity split, no stale store read. Firing 10–14 concurrent creates
  converges perfectly proxy-side.
- **The client renders exactly what it last asked for.** `TenshuPagingSource.load`
  `rawSize` matches the proxy's `resolvedSize` on every reload.
- **The failure:** under a run of writes, the Compose differ **stops running
  `load()` on new generations**. Trace showed `InvalidatingPagingSourceFactory`
  minting generations 10→15 with **no matching `load`**; `loadEpoch` (a counter
  bumped on each real `load`) froze while invalidations kept firing. The visible
  page keeps showing an earlier, smaller result. Re-entry does a fresh initial
  load (independent of the reactive path) → shows everything.

### Why the differ drops loads — PINNED to `cachedIn`

Final instrumentation (client-side, tagged `PAGINGDBG3`) logged three points:
`raw-change`, `reload-trigger → new Pager`, `pagingData emitted downstream`
(all *upstream* of `cachedIn`), and `LOAD` (inside `TenshuPagingSource.load`).

- With **`cachedIn`** (`FilesScreenViewModel.pagedFiles`): every reactive create
  produced `reload-trigger` + `pagingData emitted downstream`, but **NO `LOAD`**
  after the initial entry. `cachedIn` receives the new `PagingData` generation
  and **never runs its refresh `load()`** — it presents the cached (previous)
  pages and drops the reload. So the visible page freezes after the first load;
  a folder created while viewing never appears until re-entry (which does a fresh
  initial load, independent of `cachedIn`).
- **Without `cachedIn`** (test edit): every create logged `LOAD rawSize=1 → 2 → 3`
  and all children appeared — single, 3 s-spaced, AND 0.3 s-rapid bursts.

So `cachedIn` is THE dropper. (`InvalidatingPagingSourceFactory.invalidate()` +
`cachedIn` drops for the same reason — the invalidation mints a new generation
`cachedIn` swallows; a *fresh `Pager`* per change is one clean way to avoid
reusing the factory, but the load-bearing fix is **not wrapping the reactive flow
in `cachedIn`**.)

A secondary amplifier: one `createFolder` emits a *burst* of `changes()` signals
(~120 ms: entity save + `ParentFilesRelationMaintainer` array update + a store
write-commit echo ~280 ms later), so the pre-debounce observer minted several
generations per create — the trailing `debounce` collapses that to one.

## 3. Ruled out (with evidence — do not re-walk)

1. **Chain-DSL / lazy-vs-materialised** — the paging layer was rewritten; desktop
   passes.
2. **Transport signal loss (`changes()` dropping over Binder)** — instrumentation
   showed **dozens** of `changes()` signals arriving for a 14-create burst; the
   signal is not the lossy part.
3. **`Folder.FILES` inline-vs-out-of-line** — `isParam=true`, resolves out-of-line
   to the one `EntityFieldDataId` array writes/count use.
4. **Entity-identity split** — write-key and read-key `arrayIdHash` are identical
   on-device on every read (§2).
5. **Proxy chain-resolution / store race** — `FolderListingIdentityTest` +
   `FolderListingPersistentStoreTest` prove the proxy chain is correct
   under concurrency in-process; on-device proxy reads confirm the same.

## 4. Fix (shipped) — two load-bearing parts

**Part 1 — reactive reload** (`core-paging/.../PagingUtil.kt` `TList.pagingSource`):

```kotlin
merge(
    flowOf(Unit),                                    // initial load
    list.changes().debounce(INVALIDATION_COOLDOWN_MS).map { },
).flatMapLatest {
    Pager(config = pagingConfig) { TenshuPagingSource(context, list, projection, mapper) }.flow
}
```

Fresh `Pager` per debounced change; trailing `debounce` (`INVALIDATION_COOLDOWN_MS
= 400 ms`) collapses one create's signal burst (incl. the ~280 ms write-commit
echo) into ONE reload.

**Part 2 — drop `cachedIn`** (`FilesScreenViewModel.pagedFiles`): remove
`.cachedIn(viewModelScope)`. This is the actual bug fix — `cachedIn` swallowed
the reactive reloads (§2). `MovePickerViewModel` already omits `cachedIn`; this
matches it. Both parts are needed: Part 1 emits a reload per change, Part 2 lets
the differ actually run it.

**Verified on Medium_Phone emulator** (via a temporary `DBG_CREATE_FOLDER`
broadcast receiver → real `FilesRepository.createFolder`, driving a subfolder
`/Race/D1` etc.): single create appears; three creates 3 s apart → `LOAD
rawSize=1,2,3`, all show; six creates 0.3 s apart → all show. The `cachedIn`
variant showed only the first create, matching the report. `:drive-shared-logic`
+ `:drive-shared-ui` desktopTest suites green (no GC-thrash — that earlier concern
was the *old* self-observing PagingSource, not `cachedIn`).

**Trade-off (Part 2):** without `cachedIn`, a nav round-trip (open a photo → back)
re-collects from page 0, so a *deep-scrolled large* folder re-anchors near the top
rather than exactly in place. Correctness of the visible listing beats that niche;
if it regresses on a big folder, the follow-up is UI-side `LazyPagingItems
.refresh()` on a change signal (the differ's OWN refresh path, which `cachedIn`
*does* forward correctly) — not re-adding `cachedIn`.

## 5. The invalidate-swallow guard — superseded

The guard added a generation-counter guard for `InvalidatingPagingSourceFactory
.invalidate()` no-ops "between generations." Part 1 removes
`InvalidatingPagingSourceFactory` entirely, so the swallow case (and its guard)
no longer exist. The guard's premise — "a new generation ⇒ a reload happened" —
was shown false anyway (generations minted without loads; the real dropper was
`cachedIn`).

## 6. Test coverage

`FilesPagingReactivityUiTest` (drive-shared-ui desktopTest) drives the real
`getFilesPaged → collectAsLazyPagingItems` reactive path (now without `cachedIn`)
and asserts create-while-viewing folders appear; green on the fix. It runs over
the in-process single-ordered transport, so on-device verification of the reactive
path was done manually via the emulator (§4). A follow-up worth adding: a desktop
test that reproduces the `cachedIn`-drop (collect `pagedFiles` *with* `cachedIn`
and assert the 2nd create is dropped) so a future re-introduction of `cachedIn`
fails loudly.
