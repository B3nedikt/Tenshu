# Drive listing — Sort menu + list/grid display toggle

**Status:** **Phases 1 (Sort menu) + 2 (list/grid toggle) + 3 (persistence) +
4 (folders-first grouping + `thenBy` name tiebreak) + 5 (case-insensitive Name
sort via `lowercase()`) SHIPPED.** **iOS parity landed 2026-07-19 (§7).**
Remaining items are §6 nice-to-haves.

> ⚠⚠ **SUPERSEDED IN PART by [`drive-sorted-folder-paging.md`](drive-sorted-folder-paging.md)
> (2026-09-15).** This doc's §3 is built on **two listing modes** — a fast unsorted default and a
> sorted path that materialises the whole folder — and **neither half is true any more**. SP-3
> made the sorted listing page (one ordered chain, folders-first as a type-test sort key, a page
> per window), and SP-6 **retired natural order altogether** (D5): there is one listing, always
> sorted, defaulting to Name ascending (D6). Read that doc for the listing's actual shape; what
> survives here is the *menu* design, the persistence model and the iOS parity work.
Author: Benedikt + Claude (2026-07-12, from the Google-Drive "Sort by" reference).

Brings Drive's folder listing the sort control and the list-vs-grid display
switch from the Google Drive reference UI, as a single toolbar row below the
search bar / folder title.

---

## 1. Is `SortBy` enough? — yes, no new operation

The `sortBy` chain step (`core-chain-sort.md`) already covers the whole menu.
Every key maps to a `File` property that exists today, and the A→Z / Z→A split
is just the operation's `descending` flag:

| Menu row       | Chain step                              |
|----------------|-----------------------------------------|
| Name           | `sortBy { name }` (selector lambda)     |
| Date modified  | `sortByDescending(File.LastModifiedAt)` |
| Date created   | `sortByDescending(File.CreatedAt)`      |
| Size           | `sortByDescending(File.Size)`           |

**Name is the one that uses the selector-lambda form, not the property handle.**
`File.Name` is a *derived* (dynamic) property with no stored value; the engine's
property-sort branch reads via `getProperty`, which can't see a computed
property. The lambda form records a callstack the engine evaluates per row (the
same path `filter { name … }` uses). Value properties (Size/CreatedAt/
LastModifiedAt) use the property-handle overload and read the stored scalar.

⚠⚠ **That split is also where this feature was broken from Phase 1 until
2026-08-13:** the property-handle rows arrive as plain composites (`File` is not
an `Entity` subtype, so `root.files` resolves `isEntity = false`), the engine read
the key off an entity that wasn't there, and every row's key came back `null`.
Every comparison tied, §4's `.thenBy { name.lowercase() }` decided the order, and
**Date modified / Date created / Size showed the same alphabetical list whichever
direction was picked** while Name — the lambda arm — worked. The listing looked
sorted, which is why it read as "the toggle is broken" rather than "the sort is
dead". Diagnosis, fix and the coverage gap that hid it:
[`core-chain-sort.md`](core-chain-sort.md) §7b. Same root cause reordered Home's
"Recent files" (`recentFiles` sorts by `File.LastModifiedAt` too).

### 1.1 Google's "…by me" rows are intentionally absent

- **"Date modified by me"** would only differ from "Date modified" in a *shared
  realm* — in a personal realm every device is you. It *is* derivable from
  `FileModification` (`deviceId ∈ my device set`, max `timestamp`), but a pure
  schema-side `dynamicProperty` resolver has no DI seam to answer "which devices
  are mine", and history is retention-capped (lossy). If we ever want it, the
  right shape is a **maintained stored scalar** (`File.LastModifiedByMe`), not a
  recompute-on-read dynamic property.
- **"Date opened by me"** isn't in the data at all: `FileModification` logs
  mutations, never reads. It needs a new synced *access* event — a privacy
  decision, out of scope.

Realistic key set: **Name / Date modified / Date created / Size + A→Z / Z→A**.

---

## 2. Position — one toolbar row below the search bar

The reference puts sort below the search bar; we keep that rather than flanking
the search field, for a concrete reason: **the subfolder screen (`FolderScreen`)
has no search bar** — it shows `AppTopAppBar(title = folderPath)` and an empty
`searchBar` slot. Controls flanking the search bar would vanish in every
subfolder. A dedicated row (`FilesListingToolbar`) in the content column renders
uniformly on the root (`FilesScreen`) and subfolders: **sort chip on the left,
list/grid toggle on the right**. It is also the natural home for future filter
chips (by category).

---

## 3. The load-bearing constraint — sort can't ride windowed paging

This is the key finding, and it shaped the whole data path.

The default folder listing is **paged**: `getFilesPaged` → `toPagingData` cuts a
`drop(n).take(m)` window and only that window is projected (the O(page)-open
contract the 2k-folder ANR work established, pinned by `FilesListingEfficiencyTest`).

A **sort cannot live in that chain.** `sortBy` must see every row to order them,
so it can't be pushed below `drop().take()` — placed above it, the window can no
longer fuse and **every page load re-sorts the whole folder** (O(folder) per
window — exactly the efficiency test's "op ahead of drop/take" regression, and
the ANR-class behaviour). Sorting is inherently O(folder) (`core-chain-sort.md`
§2.3).

So the two listing modes take **two different paths**:

⚠⚠ **Historical — both bullets were retired on 2026-09-15; see the header.** They read:

- **Default (natural order)** → the fast per-window paged path, unchanged. This
  is the listing's real default (a `null` sort selection).
- **An explicit sort** → materialise the whole (ordered) folder once and wrap it
  as a single-page `PagingData.from(...)`, so the UI stays on the same
  `LazyPagingItems` surface. The whole-folder cost is only paid when the user
  opts into a sort — the fast default open is never taxed.

Consequence: **the default listing is natural (store) order, not Name-sorted**
(a deliberate divergence from Google's default-sorted listing — the O(page)-open
contract wins). The sort chip reads a neutral "Sort" until the user picks a key.

**What replaced them**: a sorted listing pages like any other, so the trade that justified two
modes is gone — and with it the divergence from Google's default-sorted listing. The chip always
names a key.

---

## 4. As-built

**Phase 1 — sort**
- `FileSort` / `FileSortKey` (`:drive-shared-logic`, `app.tenshu.drive.data`).
- `FilesRepository.getFilesPaged(folderPath, sort)` — a **new overload** (default
  body delegates to the unsorted one, so the six test fakes are untouched).
  `TenshuFilesRepository` implements it as the whole-folder `PagingData.from`
  path (§3); the one-arg overload stays the fast paged natural path.
- `FilesScreenViewModel` — `sort: StateFlow<FileSort?>` (**`null` = natural**);
  `pagedFiles` `flatMapLatest`es on it, calling the one-arg path when null and
  the sorted path otherwise.
- `FileSortBar` — the chip + two-section dropdown (keys, then direction).
- Test: `FilesPagingIntegrationTest.sortByName_ordersDerivedNames_bothDirections`
  pins the Name selector-lambda ordering end-to-end over the real proxy as a
  plain list (the repo's sorted path just wraps this in `PagingData.from`;
  `asSnapshot` can't settle on a static reactive `PagingData` flow, so the
  ordering is asserted at the chain level).

**Phase 2 — list/grid toggle**
- `FileViewMode` (LIST/GRID) in the data layer; `viewMode: StateFlow<FileViewMode>`
  + `setViewMode` on the VM (pure rendering — does not touch `pagedFiles`).
- `VerticalFileItem` — a thumbnail-forward grid tile with the same preview /
  presence / selection / more-action parity as `HorizontalFileItem`.
- `PagedFilesList` branches on `viewMode`: `LazyColumn` + `HorizontalFileItem`
  vs. `LazyVerticalGrid(GridCells.Adaptive)` + `VerticalFileItem`. The
  `itemCount == 0` empty gate is shared, so the grid inherits the "born
  non-empty" shape that dodges the desktop mouse-wheel regression.
- `FilesListingToolbar` composes the sort chip + the toggle; wired into both
  screens.

**Phase 3 — persistence**
- `DrivePreferences.ListSort` + `ListViewMode` — two **DEVICE-scoped**
  preference-service rows (`core-preference-service.md`), each a self-describing
  `TString` on the `String` adapter (no new adapter). Sort encodes as `""` =
  natural / `"<KEY>:<asc|desc>"`; view mode as the `FileViewMode` name.
- `DisplayPreferences` facade (Tenshu impl + `Noop`), symmetric with
  `SyncRulesPreferences`: resolves this device's sync identity once and
  reads/writes the per-device rows (`readForDevice`/`setForDevice`), so sort +
  view mode survive navigation into a subfolder and app restart. Cold-start
  `onStart` seeds the default (the device row is silent until first written).
- `FilesScreenViewModel` now backs `sort`/`viewMode` with the preference flows
  (`stateIn`, seeded with the fast default so the first frame renders
  immediately) and writes through on set. The facade is a trailing, `Noop`-
  defaulted constructor arg, so the iOS surface and test-Koin call sites are
  untouched; the DI graph passes the real instance.
- Tests: `DisplayPreferencesCodecTest` (the codec) + `DisplayPreferencesIntegrationTest`
  (real end-to-end round-trip through the in-process preference service).

---

**Phase 4 — folders-first grouping + name tiebreak (needs `thenBy`)**
- `thenBy` / `thenByDescending` landed in core (`core-chain-sort.md` §2.4). Its
  first consumer: non-name sort keys now break ties by name via
  `applySort` → `sortByX(key).thenBy { name }`, so equal-size / same-day files
  read alphabetically instead of in arbitrary store order.
- **Folders-first grouping** is applied on the materialised sorted list, not in
  the chain (there is no folder-ness sort key): `foldersFirst(models)` is a
  **stable** partition that keeps the chain sort order within each group, so a
  sorted listing reads folders-by-key, then files-by-key. Only the sorted view
  (whole-folder-materialised) groups; the natural/default paged view stays in
  store order (folders-first there would need sorting, breaking O(page)).
- Tests: `FoldersFirstTest` (grouping + stable order) + the `thenBy` end-to-end
  test above.

**Phase 5 — case-insensitive Name sort (the `lowercase()` fold)**
- Decided per `core-operation-dispatch.md` §4: **case-sensitive engine defaults,
  explicit opt-in at the call site** (matching Kotlin/Swift and the query-DSL
  norm — Django `icontains`, Postgres `ILIKE`, NSPredicate `CONTAINS[cd]`).
  Concretely:
  - a new `lowercase()` chain transform (`:core`, sibling of
    `substringAfterLast`); `applySort` folds it into the selectors —
    `sortBy { name.lowercase() }` + `thenBy { name.lowercase() }` tiebreaks,
    Kotlin's own `sortedBy { it.name.lowercase() }` idiom. The engine comparator
    stays case-sensitive like Kotlin's `sorted()`.
  - `contains` flipped to **case-sensitive by default** (it had been silently
    `ignoreCase = true` while borrowing Kotlin's case-sensitive syntax); Drive's
    search opts in explicitly via the new `contains(needle, ignoreCase = true)`
    overload (an optional op param — plain `contains` stays byte-identical).
    Search behaviour is unchanged for users.

## 5. Known limitations

- ~~**Default listing is unsorted (natural order)** — §3. Sorting is opt-in;
  folders-first grouping applies to sorted views only (see Phase 4).~~ ✅ Gone (SP-6): one
  listing, always sorted, folders always first.
- ~~**Sorted view loads the whole folder** in one emit (`PagingData.from`) — the
  unavoidable price of sorting; the default open stays O(page).~~ ✅ Gone (SP-3): it was not
  unavoidable — it needed four gaps in the query engine closed. A sorted page is now one bounded
  thin pass.
- **Name ordering is locale-invariant** (`lowercase()` + code-unit compare) —
  no locale-aware collation (e.g. `ä` sorts after `z`); a collator is a
  deliberate future decision, not an oversight.

---

## 6. Outstanding

1. **`File.LastModifiedByMe`** maintained scalar — only when shared folders make
   "modified by me" meaningful (§1.1).
2. A Pager-backed sorted path (materialise-once + window the cached sorted list)
   if the whole-folder sorted emit ever proves heavy on very large folders.
3. Optimistic local update for the view-mode toggle if the preference-service
   write→read round-trip proves visibly laggy on device (currently write-through).
4. Build-time `thenBy` fold (a chain `replaceLast`) to drop the redundant
   intermediate sort, if a deep `thenBy` chain ever matters (`core-chain-sort.md` §2.4).
5. Locale-aware collation for Name ordering (see §5), if/when localisation
   reaches n=2.

---

## 7. iOS parity (as-built, 2026-07-19)

The whole feature was already cross-platform in `:drive-shared-logic` — `FileSort`
/`FileSortKey`, `FileViewMode`, `DisplayPreferences`/`TenshuDisplayPreferences`, and
`FilesScreenViewModel.{sort,setSort,viewMode,setViewMode}`. Drive-on-iOS (route-1,
`ios-drive-parity.md`) simply wasn't consuming any of it: the SwiftUI `FilesView`
rendered a bare `List` and the VM was built with the default `NoopDisplayPreferences`.
Bringing iOS to parity was **wiring + native UI, no new shared logic**:

1. **Persistence seam.** `DriveReadSurface.installPreferences(…)` gained a third,
   trailing-defaulted `displayPreferences` slot (symmetric with the P5 offline/sync-rules
   installs); `DriveLocalRuntime.install()` and `installRealClient()` install
   `TenshuDisplayPreferences(client)` over the same in-process/EPC client, and
   `DriveReadSurface.create(…)` threads the installed instance into the
   `FilesScreenViewModel` constructor. Fakes keep the Noop seam (so previews/tests are
   unaffected).

2. **Swift bridge.** `FilesModel` mirrors the VM's `sort`/`viewMode` `StateFlow`s
   (SKIE `AsyncSequence`, same pattern as `snapshot`/`refreshSettled`) into `@Published`
   properties and forwards `setSort`/`setViewMode`. A sort change re-runs `pagedFiles`
   (the VM `flatMapLatest`s on `sort`), which the presenter is already collecting — the
   snapshot refreshes with no extra plumbing.

3. **Native UI (idiomatic, not a Compose port — HR#5).** Rather than Android's
   two-control toolbar row, iOS uses the **Files.app idiom**: one toolbar `Menu`
   (`ellipsis.circle`) holding inline `Picker`s — a **View** section (Icons/List), a
   **Sort By** section (None + Name/Date Modified/Date Created/Size), and an **Order**
   section (shown only once a key is chosen). Inline pickers render the active selection
   as a native checkmark for free. The listing body switches between `List` (rows) and a
   `LazyVGrid` of thumbnail-forward `FileTile`s; both share the viewport-driven,
   cancel-on-scroll thumbnail loader with `FileRow`.

Decision kept for parity: **`sort == nil` (natural order) is a real state** — the fast
paged path (§3). The iOS Sort By picker surfaces it as an explicit "None" row rather than
implying a sort is always active. ⚠ **The "None" row is superseded by §8** — the *state*
stays, the menu item is gone.
⚠⚠ **And the state itself is gone since SP-6 (2026-09-15)**: `sort` is non-optional on both
platforms, so a sort *is* always active. The iOS picker keeps one key checked and always shows
Order.

Verified: `:tenshu-sdk-ios:iosSimulatorArm64Test` green (`DriveLocalRuntimeTest` et al.);
`DriveIos` simulator `xcodebuild` green. On-device tap-through pending (P7 pass, same as
the rest of `ios-drive-parity.md`).

---

## 8. "Sort does nothing on iOS" — the identity race, and dropping the "None" row (2026-07-21)

**Report.** *"The Descending/Ascending toggle on iOS seems to do nothing — it never
actually switches the sorting. Is it wired up at all?"*

It was wired up, and to the same `FilesScreenViewModel` Android drives. Two separate
things were nonetheless wrong, one real bug and one UI decision that disguised it.

### 8.1 What the investigation ruled out

- **Shared logic is fine.** New coverage — `SortToggleIntegrationTest`
  (`:drive-shared-ui` desktopTest) — composes the stack exactly as iOS composes it
  (real VM + real `TenshuDisplayPreferences` + one long-lived `TenshuPagingPresenter`
  collecting `pagedFiles` for the screen's lifetime) and flips the sort underneath it:
  natural → asc → desc → cleared all reorder the presenter snapshot. The pre-existing
  tests missed this shape — `DisplayPreferencesIntegrationTest` re-subscribes a *fresh*
  `observeSort()` per assertion (so a read flow that emits once and completes still
  passes it), and `FilesPagingIntegrationTest` exercises the chain below the VM.
- **A failing sorted chain is not the explanation.** The repository maps a chain failure
  to `PagingData.empty()`, and an empty generation *does* clear the presenter snapshot —
  so a broken sort renders an **empty folder**, never a frozen one.

### 8.2 The real bug — one-shot device-identity resolution

Every per-device Drive preference seam resolved the device id **once, at subscribe**, and
on a blank result emitted a default and let the flow **complete**:

```kotlin
override fun observeSort(): Flow<FileSort?> = flow {
    val id = deviceId() ?: run { emit(null); return@flow }   // ← completes, forever
    emitAll(…)
}
override suspend fun setSort(sort: FileSort?) {
    val id = deviceId() ?: return                            // ← silently no-ops
    …
}
```

A screen holds its subscription for its whole lifetime, so `stateIn` never restarts that
upstream: **a screen that opened before sync identity was up stayed on the defaults for
the rest of the session** — reads never re-emitted, writes silently vanished. It is
unrecoverable without leaving the screen, and invisible (no error, no log).

iOS route-1 is the most exposed platform, because `DriveLocalRuntime.install()` and the
Files screen come up in the same breath; Android reaches Control over Binder, which is
usually up before Drive's first listing. Nothing about the race is iOS-specific, though.

**Fix:** `DeviceIdentity` (`:drive-shared-logic`, `app.tenshu.drive.data`) — a small
retry-and-cache resolver. `await()` suspends until the id lands (100 ms → 2 s backoff,
cancellation is the exit); `awaitOrNull(timeoutMs)` bounds it for writes so a user gesture
degrades to the old no-op rather than parking a coroutine. A throwing proxy (no Control
paired, IPC not up) counts as unresolved and is retried — with `CancellationException`
rethrown, never swallowed. Read flows now **seed their default first, then await**:

```kotlin
override fun observeSort(): Flow<FileSort?> = flow {
    emit(DrivePreferences.ListSort.default.decodeSort())     // render immediately
    emitAll(readForDevice(DrivePreferences.ListSort, identity.await()).map { … })
}
```

All five seams were converted (they were five copies of the same body):
`TenshuDisplayPreferences`, `TenshuOfflinePreferences`, `TenshuSyncRulesPreferences`,
`TenshuBackupPreferences`, `TenshuIngestRegistry`. Each takes a defaulted
`identity: DeviceIdentity` so tests can inject one. `DeviceIdentityTest` pins the
retry, the caching, the write timeout, and the throwing-proxy case.

### 8.3 The "None" row — removed (§7 superseded on this point)

`sort == null` is a genuine state and stays: it is the fast per-window paged path (§3),
which any sort must give up. But it is **not a user-meaningful menu choice**, and as a
selectable row it actively disguised §8.2: when a preference write was dropped, the menu
snapped back to *None* — taking the Order section with it, since that renders only when
`sort != nil`. The control looked broken rather than unwritten.

Android never offered it: `FileSortBar` lists four keys + two directions and its
`onSortChange` is non-null-typed, so natural order is the *pre-choice* state (neutral
"Sort" chip, nothing checked) and is unreachable once you sort. The iOS picker now matches
— no "None" row, nothing checked until the first pick. Files.app, Google Drive and Dropbox
all keep an active sort key at all times, so this is the platform-idiomatic shape too.

Verified: `:drive-shared-logic:desktopTest` + `:drive-shared-ui:desktopTest` green
(296 tests), `DriveIos` simulator `xcodebuild` green, Android smoke gate green.

**On-device pass DONE (2026-07-22, iPhone 17e, signed device build of `d4692fe1` via
`scripts/ios-smoke.sh --build drive`):** cold launch, sort applies and the checkmark
sticks. That is the assertion the automated gates structurally could not make — the
failure was a *startup race*, so it only ever reproduced on a cold launch against a
real proxy, and `ios-smoke.sh` asserts liveness (pid alive after 6 s), never taps.
The sort menu is therefore the first confirmation that `DeviceIdentity` closes it in
production; the other four seams (offline pins, sync rules, backup, ingest) inherit
the same fix but have not been separately re-checked on device.
