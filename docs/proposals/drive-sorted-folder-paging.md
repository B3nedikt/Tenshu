# Sorted folder listings that page

**Status**: **COMPLETE — v1.4.** Design v1.0 with all decisions D1–D6 taken (§8); **SP-1 … SP-6
shipped 2026-09-15** (as built: §10), device pass done (§10.7: first paint 2.8 s → 1.1 s and flat
in folder size). Drive has one folder listing, always sorted, folders first, Name ascending by
default. ⚠ What outlives the track: a `:core` op is fenced by a **storage-format bump**, not by
dispatch (§10.2), and the projected change observer is the dominant per-open cost (§10.7 finding 4,
`tech-debt.md`). §6 is the agreed design, §7 its phases, §2.2 and §5 the measurements and prior art
behind it.
**Motivating case**: `/Backup/OnePlus CPH2581/Camera` (618 rows) spun for 25 s under a
"Date modified" sort on a OnePlus, then appeared all at once. The linear-time list fold took that to ~2 s; this doc is
about the part the fold did not change — a sorted listing is not paged at all.

---

## 1. The question this exists to answer

*"Paging works everywhere else — why does a sorted folder load the whole folder? Do we really need
two streams (folders, then files), or would something like a `groupBy(isFolder())` solve it? And
would that be efficient with the engine as it is, without new peepholes that look backwards in the
operation stack?"*

Short answers, argued below:

- The whole-folder path is not a limitation of `:core-paging` — ordered windows page fine since
  `core-ordered-window-fusion.md` F2. It is **four query-side gaps** (§3), and the design doc that
  owns the sort names only one of them.
- **No, two streams are not needed.** Folders-first is an *ordering*, and the chain can already
  express it as a first sort key whose value is a predicate. `groupBy` is the wrong operator — it
  produces groups, not an order (§4.2).
- **A predicate key is correct today but not fast today**, and a *generic* predicate key never can
  be. What makes it fast is widening the existing fusion gate (`classifySortKey`) by three narrow
  key kinds — a type test, a value property on a composite row, and a stored field through a pure
  transform (`path.lowercase()`). That is a *forward* peephole that already exists; nothing has to
  look backwards (§4.4).

## 2. What happens today

`FilesScreenViewModel.pagedFiles` (`FilesScreenViewModel.kt:236`) picks one of two repository
paths on the persisted sort:

| sort | path | what runs |
|---|---|---|
| `null` (natural order) | `getFilesPaged(folder)` (`TenshuFilesRepository.kt:376`) | `toPagingData` window `drop(p).take(20)`, projected per page — **0.4 ms/page at 10 000 rows** (§2.2) |
| any key | `getFilesPaged(folder, sort)` (`:447`) | `applySort(sort).select{…}.flow()` → **one** `PagingData.from(foldersFirst(all rows))` |

⚠ `FileSortBar`'s callback is `(FileSort) -> Unit` (`FileSortBar.kt:52`): once a user picks a sort
there is no way back to `null`, and the choice is persisted per device (`drive.listing.sort`). On a
device where anyone ever touched the sort chip, **every** folder takes the unpaged path.

### 2.1 Measured on the device

OnePlus CPH2581, debug build, "Date modified" descending:

| folder | rows | before the linear fold | after |
|---|---|---|---|
| Camera | 618 | 25 s, 25 s | 2.3 s, 2.1 s, 2.2 s |
| WhatsApp Images | 830 | 45 s | 2.5 s |

The linear fold removed a client-side O(n²) fold (`TList.flow`'s merge re-hashed every held row per arrival).
What remains grows with the folder: every open reads, sorts and ships every row, and every change
re-runs the whole sorted query on Control, whose change diff is itself old×new
(`StreamRegistry.kt:228,253`). The fully backed-up Camera folder on that device would be ~2 300
rows.

### 2.2 Measured on the JVM

In-process proxy (`createFileProxy` + `LocalTaskExecutor`), the real `navigateTo` chain and the real
Drive projection, `getAll()` one-shot, median of 5 after warm-up; 1-byte files, `LastModifiedAt`
clustered with many ties (as a backup produces). In-memory store; SQLite medians were within ~10 %.
No IPC, no client mapping — on the phone both come on top, and per-call constants were ~10×.

| # | shape | 2 000 rows | 10 000 rows | store reads @10k | fused? |
|---|---|---|---|---|---|
| 1 | whole folder, `sortByDescending(LastModifiedAt).thenBy { name.lowercase() }` | 45 ms | 189 ms | 60 044 | no |
| 2a | same sort, page at p = 0 | 19 ms | 77 ms | 30 084 | **refused** |
| 2b | same sort, page at p = N/2 | 17 ms | 78 ms | 30 084 | **refused** |
| 2c | `sortByDescending(LastModifiedAt)` alone, p = 0 | 7 ms | 35 ms | 20 079 | **refused** |
| 3a | `sortBy { name.lowercase() }`, p = 0 | 5 ms | 22 ms | 10 074 | refused |
| 4a | natural `drop(p).take(20)` — today's paged path | 0.4 ms | 0.4 ms | 94 | window fold |
| 5 | `sortByDescending { isMedia() }.thenByDescending(LastModifiedAt)` — a predicate first key | 18 ms | 66 ms | 20 079 | refused |
| 6 | `count().resolve()` | 0.1 ms | 0.1 ms | 8 | count short-circuit |
| 7a | `sortBy { path }` — a single stored field, p = 0 | 3 ms | 13 ms | 10 074 | **fires** |
| 7b | same, p = N/2 | 5 ms | 9 ms | 10 074 | **fires** |
| 8 | Photos' keyset shape: `filter { createdAt.inRange(…) }.sortByDescending(CreatedAt).take(20)` | 16 ms | 60 ms | 25 080 | no (filter defers) |

Reading it:

- ⚠⚠ **Paging a date sort today saves only ~60 %** (2a vs 1): the page is cut in memory after a full
  unfused sort, so every page re-reads the whole folder — three store reads per row.
- ⭐ **A field key already fuses on these very rows** (7a/7b): time roughly flat in position, one
  read per row, only the top-(p + 20) projected and sorted. Most of the gap to 2c is the refusal in §3 B2 — an extra
  read and a `DataId` computation per row.
- A predicate first key (5) costs what its other keys cost; it is not the expensive part.

### 2.3 Measured on the built listing — a sorted page is O(folder), not O(page)

⚠⚠ **This is the limitation to design against, and it survives SP-6.** §2.2 benched candidate
*shapes*; this benches the listing that shipped (SP-3 + SP-6, merged with master),
through the real repository — `getFilesPaged(path, FileSort(MODIFIED, descending))` collected with
`boundedSnapshot { scrollTo(n) }` over `FilesListingEfficiencyTest`'s counting store. 2026-09-16.

**Depth is free.** `navigateTo` chains one `search<Folder> { path equalTo … }.files` per segment, so
a nested folder reaches `sortBy` with a different receiver than the root — but the fusion gate
(`operationContext.executionStack.isEmpty()`) is not disturbed by it:

| receiver, 150 rows, open + 4 windows | store reads |
|---|---|
| `/big` (depth 1) | 2 112 |
| `/Backup/Phone/Camera` (depth 3) | 2 146 |

**Size is not.** Same open + 4-window scroll, one level deep:

| folder size | store reads | per row in the folder |
|---|---|---|
| 150 | 2 111 | 14.1 |
| 600 | 7 727 | 12.9 |
| 1 200 | 14 927 | 12.4 |

**And the cost is per *page*, not per open** — at 1 200 rows, by windows loaded:

| windows | store reads | marginal |
|---|---|---|
| 1 | 6 406 | — (first load + the observer's projected pass, ≈ 5 × folder) |
| 2 | 12 397 | +5 991 |
| 4 | 14 953 | **+1 278 per window ≈ 1.07 × folder size** |

⭐ **The fusion is working; it just cannot bound this.** F1 bounds the *sort* and the *projection* —
each row is read once, thin, and only the surviving 20 are projected — but to know which 20 rows
come next in an order you must look at all of them, so every page load is one full pass over the
folder. `TenshuPagingSource`'s KDoc states it ("fusion bounds the sort, not the scan") and it is why
the page read was moved off the caller's thread; what was missing was a number.

⚠⚠ **Extrapolated to a real backup folder** — `/Backup/OnePlus CPH2581/Camera`, ~3 900 files — that
is **~20 000 reads to open and ~3 900 per scroll page**, against 2 111 for the whole scroll at 150
rows. This is the gap between "fine at the root" and "slow in a backup folder"; the root holds a
handful of entries, so its O(folder) term is invisible.

⚠⚠ **`FilesListingEfficiencyTest` pins the wrong axis and will stay green through this.** It fixes
`folderSize = 150` and asserts a *total* `calls < 22 × folderSize`, which catches a constant-factor
regression — a key falling off the fused path, §3 B2/B6 — but says nothing about the O(folder) term,
because at one size a linear term and a constant are indistinguishable. What would pin it is a
second case at ~2 000 rows asserting the **marginal** cost of one further window (expect ≈ 1 ×
folder size, fail above ~2 ×). Until then the budget certifies the wrong thing at a size no real
backup folder has.

**Bounding it is keyset paging, not more fusion.** `KeysetPagingSource` and `core-keyset-paging.md`
are already in the tree: carry the last row's sort key and resume from it, and a page becomes
O(page) *given an ordered index to resume into* — which is the part that does not exist yet, and is
a design change rather than a peephole. Recorded here as a known limitation of the shipped listing,
not a blocker for it.

## 3. Why a sorted listing cannot page today

`core-ordered-window-fusion.md` §6 (F2) recorded the blocker as *"no longer the sort — it is
`foldersFirst`"*, and the KDoc on `getFilesPaged(folder, sort)` still says so (SP-3 corrects it).
That is one of four; the other three were never written down — and a fifth (B6) surfaced only
when the first four were out of the way.

- **B1 — folders-first has no key.** `foldersFirst` (`TenshuFilesRepository.kt:1432`) reorders the
  *materialised* list, so applied per page it would float page 3's folders above page 1's files.
  There is also **no chain predicate for folder-ness at all**: `FileCategory` has no folder value
  (`FileCategory.kt`), and `filter<Folder>` / `search<Folder>` only *narrow the predicate's type*
  (`Filter.kt:26`, `OperationRo.narrowedElementType`) — they filter nothing.
- **B2 — value-property keys are refused on `File` rows.** `Folder.FILES` is a list of `File`, and
  `File : Composite`, so the array reads with `isEntity = false`; `classifySortKey` then refuses
  every value-property key (`OrderedWindowFusion.kt:153`). The fall-through is expensive for a
  reason beyond the sort: the deferred stack re-enters **without its projection**, so every row is
  fully deserialised, its relation map computed and discarded (`DbEntryMapper.kt` — composite rows
  keep fields only), and `SortByImplementation.extractKey` then computes a `DataId` (trait lookup +
  pure op) and **re-reads the row as an entity** to reach the property it just dropped
  (`SortByImplementation.kt:113`). That is shape 2c's two reads per row.
  ⚠ The stored row already carries those properties (`ObjectStore.DbEntry.properties`); the second
  read exists only because composite rows discard them.
- **B3 — multi-step keys are refused.** `selector.singleOrNull() ?: return null`
  (`OrderedWindowFusion.kt:140`): `name.lowercase()` (the Name sort, and every tie-break in
  `applySort`), and any predicate such as `isMedia()`, fall through. Dynamic properties are refused
  even as a single step.
- **B4 — offsets drift under "newest first".** `TenshuPagingSource` keys pages by offset and
  dedupes nothing. A backup inserts at the *head* of a date-descending listing, so an append that
  lands between the insert and the reload re-serves the previous page's last row — and Drive keys
  rows by `path` (`PagedFilesList.kt:133`), so a duplicate is Compose's *"Key was already used"*
  crash, not a cosmetic glitch. The natural-order path does not meet this *(inferred, not tested)*:
  uploads append to the folder's member array, so that listing grows at its tail. Keyset paging (Photos' answer) cannot carry this ordering:
  `KeysetKey<K>` is one cursor value (`KeysetPagingSource.kt:47`), and the predicate vocabulary has
  no `or`, no tuple comparison and no string `inRange`, so a `(isFolder, date, name)` cursor is not
  expressible as a window.

- **B6 — a `thenBy` leaves its prefix ops on the chain** *(found 2026-09-15 while wiring SP-3;
  not in v0.1)*. `SortByRo.foldThenKey` **appends** a `SortBy` carrying the full key list and
  leaves the prior one in place (the chain wrapper cannot be truncated), so
  `sortBy(A).thenBy(B).thenBy(C)` arrives as `SortBy(A) · SortBy(A,B) · SortBy(A,B,C)` ahead of
  the window. The gate looked at one `SortBy` and expected a window next; it saw another
  `SortBy` and fell through — so **no tie-broken sort ever fused**, whatever its keys, which is
  every entry of Drive's sort menu. §2.2's shape 2a was refused for this as much as for B3.
  ✅ Fixed in the engine PR: `foldSortByRun` collapses a run of consecutive `SortBy` ops in which
  each extends the last (same prefix, same directions, one more key) to its last member before
  the gate. Sound because every pass is stable and rows equal under the full key are equal under
  each prefix; two *unrelated* sorts (`sortBy { a }.sortBy { b }`) are not a run and stay unfused.
  Pinned by `thenByRun_foldsToItsLastOp_andFuses` / `unrelatedConsecutiveSorts_areNotARun_andStayUnfused`.

And one constraint on any design:

- **B5 — invalidation must observe the unsorted chain, and must see what the row renders.**
  `pagingSource` observes its *receiver* (`PagingUtil.kt:255`, `reloadSignals`). A signal-only
  stream still evaluates its whole chain on subscribe and on every update, then diffs old×new
  (`StreamRegistry.kt:130–262`) — so paging a sorted receiver would re-sort the folder on every
  write.
  ⚠⚠ But the *bare* unsorted chain is blind to exactly the writes that reorder a date or size sort —
  and to every write that changes what a row shows without changing its `path`.
  An overwrite is in place — same `dataId`, bumped metadata (`ReplaceFile`, and a peer's sync lands
  the same way) — and `equalToLogic` compares **properties only between entities**; a `File`
  composite compares its fields, i.e. `path` alone. So an overwrite produces no signal, no new
  `Pager` generation, and an offset listing that stays mis-ordered: worse, a row that moves from the
  unloaded tail to above the loaded boundary is **never served** until something else invalidates
  (the dedupe of §6 cannot help — nothing is duplicated, something is missing).
  *(Read from code:)* today's whole-folder sorted path does receive that change — its stream is over
  a projected chain, so rows are entities and the diff sees the property — but `StreamRows.merge`
  replaces the row at its old index, so its *position* is already stale there too. The natural path
  gets no signal at all (a stale size/date subline).

## 4. Options

### 4.1 Two segments — folders, then files

A `:core-paging` source that serves `folders.sorted` for positions `[0, F)` and `files.sorted` from
`F`, with `F` from a count.

- ✅ Keeps each segment's order single-keyed, so a **per-segment keyset cursor** becomes possible
  for date sorts (not for Name — no string range), which is its one real advantage (B4).
- ❌ Needs the folder-ness predicate (B1) **and its negation**, and there is no `not`.
- ❌ Each segment is a *filtered* chain, and `filter` defers onto the execution stack, which every
  peephole requires to be empty — so **neither segment can ever fuse** (shape 8), and
  `filter{…}.count()` misses the count short-circuit too: `F` costs a full scan, not shape 6.
- ❌ A new paging primitive with its own seam (offset spill across segments, two invalidations).

Cost per page ≈ shape 2a over each segment. **No efficiency advantage** over §4.3, more surface.

### 4.2 A `groupBy` / `partition` operator

- `groupBy(key)` returns *groups*: `Map<K, List<T>>` in Kotlin, a stream of sub-streams in
  Reactor/Rx whose interleaving is unordered. Neither is a sequence with offsets, so neither pages.
- `partition(predicate)` read as "matching rows first" **is** a stable sort on a boolean key.
  `SortByImplementation` sorts stably (`sortedWith`, `:67`) and the fused path breaks ties by
  encounter index (`OrderedWindowFusion.kt:98`), so `sortByDescending { predicate }` already *is*
  that operator. A new op would only be faster if a gate recognised it — which is §4.4 with a
  different spelling.

### 4.3 One ordered list with a predicate first key

```kotlin
.sortByDescending { isFolder() }          // folders first — a boolean key
.thenByDescending(File.LastModifiedAt)    // the user's key
.thenBy { path.lowercase() }              // tie-break
```

⚠ The tie-break is `path.lowercase()`, not today's `name.lowercase()` and not the raw `path`.
Siblings share their parent prefix, so it orders exactly like `name.lowercase()` — preserving
today's case-insensitive ties — while being a stored field through a pure transform (§4.4 kind 3).
The raw `path` would fuse without new work but is case-sensitive, a visible change among ties
("Zebra" before "apple"), and ties are **common** in a backup folder because upload times cluster.
The Name sort itself becomes `sortBy { path.lowercase() }` for the same reason.

paged by `TenshuPagingSource`, with this chain supplied as its `ordering` over an unsorted receiver
(§6 shows the call).

- ✅ Correct today once B1's predicate exists: `compareSortKeys` handles `TBoolean`, the sort is
  stable, and `foldersFirst` disappears from the client.
- ✅ Generic: any predicate can lead an ordering, exactly as `filter` takes any predicate.
- ❌ **Not fast.** Every key is refused (B2/B3), so each page is at least shape 5:
  ~66 ms at 10 000 rows on the JVM, paid again for every page. First paint is roughly today's
  whole-folder query; the saving is only IPC and client mapping of 20 rows instead of N.

### 4.4 §4.3, plus three more key kinds in the existing gate  ⭐ chosen (D1)

The peephole already looks *forward* from `sortBy` to the window and the `select`
(`EvaluateCallstack.kt`, the ordered-window fusion branch); all four peepholes do. What refuses
§4.3 is `classifySortKey`, which accepts two `SortKeyAccess` kinds. Add three:

1. **`ValueProperty` on a composite row** (B2). The fused read already widens its projection with
   `keepProperties` for property keys; let the thin read keep them for composite rows too (or read
   the scan `asEntity`), and drop the `isEntity` refusal. One read per row, no `DataId`, no re-read.
2. **`TypeIs(schemaId)`** for a *type-test* selector (B1/B3). A row's type id is on the stored entry
   and survives deserialisation (`DbEntryMapper` reads `dbEntry.type` into the row), so the key is
   that type id checked for subtype — free. ⚠⚠ *Where* the id sits depends on how the row was read:
   a plain composite carries it as its `schema`, but an entity's `schema` is always `Entity.Schema`
   (`Entity.kt`) and the real `File`/`Folder` id is `Entity.type`. Kind 1 may read the scan
   `asEntity`, and every date/size chain combines both kinds, so `readSortKey` must take the id from
   `Entity.type` for entities and from `schema` only for composites — checking `schema` alone would
   read every folder as not-a-folder and mix them into the files.
3. **`FieldTransform(field, ops)`** for a stored, non-optional field followed only by *pure scalar*
   operations — `path.lowercase()` (B3, narrowly). The field is already kept on the thin row
   (kind `Field`, shape 7a); the transform is the op's pure logic (`lowercaseLogic`) applied to it.
   It is what keeps case-insensitive ordering (D4) without falling off the fused path.

With the three kinds, every key of §4.3 fuses. Expected cost ≈ shape 7a: ~10 ms per page at 10 000
rows on the JVM, roughly flat in position. ⚠ *Inferred* from 7a and the read counts, not measured —
SP-1 measures it.

⚠⚠ **What fusion does not bound is memory.** `DatabaseImpl.getArrayData` materialises the scan with
`.toList()` inside the store lock — deliberately, so a lazy read cannot race concurrent writes — so a
fused page still holds **all N thin rows** for the length of the pass, plus a top-(p + n) heap that
grows with the offset (`core-ordered-window-fusion.md` §8 records the eager pass as an F1 as-built
delta). The win is the projection and the skipped full sort, not an O(p + n) resident set, and a deep
page's heap approaches O(N). At thin-row sizes that is acceptable for this track; a genuinely
bounded page needs a snapshot-safe streaming read (a lazy cursor that holds the lock, or a
copy-on-write snapshot), which is the F3-adjacent follow-up that doc names — not a prerequisite
here, and SP-1 should report peak heap alongside time so the choice is made on a number.

The generic-vs-fast trade the question anticipated, made explicit: **an arbitrary predicate key
stays correct and stays on the slow path** (one read + a callstack evaluation per row, shape 3a);
only the *type test* is recognised. That is the same bargain `filter` already strikes — any
predicate works, only some are pushed down — and it keeps the gate's contract (a key the thin row
already carries, at most passed through a pure scalar op) instead of teaching it to interpret
arbitrary callstacks.

### 4.5 Keep the whole-folder path

Fix the proxy diff to O(n) (the `StreamRows` treatment, on `StreamRegistry`) and stop there.

- ✅ Smallest change; the diff fix helps **every** stream consumer, so it is worth doing regardless
  (SP-5).
- ❌ Open cost stays O(N) query + N rows over IPC + N client mappings (2.1–2.5 s on the device at
  618–830 rows); each change stays a full re-sort. Folders the size of a real camera roll do not
  fit.

### 4.6 Summary

| | new surface | per page @10k (JVM) | drift (B4) | generic |
|---|---|---|---|---|
| 4.1 two segments | predicate + `not` + paging primitive | ≈ 2a per segment, never fuses | keyset possible (dates only) | no |
| 4.2 `groupBy` | — | does not page | — | — |
| 4.3 predicate key | predicate | ≈ 66 ms (shape 5) | offsets + dedupe + bounded reload | yes |
| **4.4 predicate key + gate** | predicate + 3 key kinds | **≈ 10 ms (inferred from 7a); memory O(N) thin rows** | offsets + dedupe + bounded reload | yes, type test fast |
| 4.5 whole folder | diff fix | N/A — O(N) per open and per change | none | yes |

## 5. How others do it

Surveyed 2026-09-15; a claim not confirmed from a primary source is marked *unverified*.

- ⭐ **Folders-first is a type-first sort key nearly everywhere it exists — never a split or a
  grouping operator.**
  - Box's folder-items API is always sorted "by their type first, with folders listed before
    files", and the caller's `sort` is only "the second attribute"
    ([Box](https://developer.box.com/reference/get-folders-id-items/)).
  - Google Drive's `files.list` accepts `folder` as an `orderBy` key (`folder,modifiedTime desc,name`)
    ([Drive v3](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list));
    *unverified* that it means "folders first".
  - Clients put the directory key first in a comparator over a fully loaded folder: Android
    DocumentsUI ("Directories always go in front",
    [SortingCursorWrapper](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/heads/main/src/com/android/documentsui/sorting/SortingCursorWrapper.java)),
    Nextcloud's web client ([fileSorting.ts](https://raw.githubusercontent.com/nextcloud-libraries/nextcloud-files/main/lib/utils/fileSorting.ts)),
    and Windows Explorer's buckets ([Old New Thing](https://devblogs.microsoft.com/oldnewthing/20080804-00/?p=21383)).
  - Several server APIs offer nothing: Dropbox `list_folder` has no sort at all, and Graph/OneDrive's
    `$orderby` has no folder key ([OneDrive](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/optional-query-parameters)).
- ⚠ **Folders stay first when the user flips the direction** — Nextcloud's type key is ascending
  regardless of the chosen direction — so the ordering has *mixed* directions. §4.3's type key
  must keep its own direction fixed; the fused comparator already carries a descending flag per key
  (`OrderedWindowFusion.kt:93`), so this costs nothing here, but it is what makes a SQL row-value
  keyset awkward (next bullet).
- **Indexes and cursors are why a computed key is hard elsewhere, and neither applies to this
  engine yet.** SQLite serves an `ORDER BY` expression from an index only when the index holds the
  *same* expression ([expridx](https://sqlite.org/expridx.html)); a row-value seek `WHERE (a,b) >
  (?,?)` ([rowvalue](https://sqlite.org/rowvalue.html)) cannot mix directions
  ([Cybertec](https://www.cybertec-postgresql.com/en/keyset-pagination-with-descending-order/));
  Firestore can only order by stored fields
  ([Firestore](https://firebase.google.com/docs/firestore/query-data/order-limit-data)). Tenshu has
  no ordered index (F3 is deferred), so the fused path is the equivalent of SQLite's "use temp
  B-tree for ORDER BY": one scan plus a bounded heap. A type key read off the row costs that scan
  nothing extra, which is the whole of §4.4's argument.
- **Two queries concatenated is a cursor optimisation, not a listing model.** SQL gives a `UNION`
  no order guarantee ([PostgreSQL](https://www.postgresql.org/docs/current/queries-union.html));
  where it is used deliberately it rewrites the OR-expanded condition of a *composite keyset
  cursor* (GitLab's `use_union_optimization`,
  [GitLab](https://docs.gitlab.com/development/database/keyset_pagination/)). If D3 ever moves to a
  composite cursor without an `or` operator, two segments come back as that — an implementation
  detail of §4.4's cursor, still under one ordering.
- **Grouping operators do not page.** Reactor's `groupBy` "works best with a low cardinality of
  groups" and needs every group drained ([Flux](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html));
  Rx buffers unsubscribed groups ([ReactiveX](https://reactivex.io/documentation/operators/groupby.html));
  Kotlin's `Flow.groupBy`, Java's `partitioningBy` and Kotlin's `partition` are terminal and return
  collections. Emitting folders-then-files from any of them buffers the files group.
- **Offsets are the norm for this shape, with the known caveat.** Room's paging helper is
  `COUNT(*)` + `LIMIT/OFFSET` ([RoomPagingUtil](https://github.com/androidx/androidx/blob/androidx-main/room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/util/RoomPagingUtil.kt)) —
  the same model as `TenshuPagingSource`. Seek pagination avoids drift but needs a unique
  tie-break and cannot jump or page backwards
  ([use-the-index-luke](https://use-the-index-luke.com/sql/partial-results/fetch-next-page)),
  which infinite scroll does not need — the case for D3 staying open rather than closed.
- **Product precedent varies.** Finder's "Keep folders on top" applies only when sorting by name
  ([Apple](https://support.apple.com/en-hk/guide/mac-help/mchlp1745/mac)); Google Drive web offers
  "On top" / "Mixed with files" ([help](https://support.google.com/drive/answer/2375177)). Whether
  folders stay first under every key is a product choice (D5), not a given.

## 6. The design (decided)

**§4.4.** One ordered chain, folders-first as a type-test first key, paged by the existing
`TenshuPagingSource`; make it fast by widening `classifySortKey`, not by adding a paging primitive
or a look-behind. It is also the shape the rest of the industry converged on (§5): a type-first key,
not a split. Concretely, for Drive:

- The listing order moves **into the chain**: `applySort` becomes
  `sortByDescending { isFolder() }.then(<user key>).thenBy { path.lowercase() }` (the Name key is
  `path.lowercase()` itself); `foldersFirst` and `WHOLE_FOLDER_LOAD_STATES` are deleted.
- The sorted path joins the paged path — with an **unsorted receiver** and two new hooks:

  ```kotlin
  root.files.navigateTo(folder)                             // receiver: unsorted, never projected
      .toPagingData(
          mapper = fileMapper::map,
          ordering = { it.listingOrder(sort) },              // the chain above; cut into pages in load()
          invalidation = { it.select { listingRow() } },     // what reloadSignals() observes
      ) { window -> window.select { listingRow() } }         // per-page projection, as today

  // everything FileMapper renders: fields(File.Field.PATH); relations(File.Category, File.Size,
  // File.CreatedAt, File.LastModifiedAt, Folder.FileCount) — today's projection, no File.Preview
  ```

- **B5**, why two hooks and not one receiver: `pagingSource` calls `reloadSignals()` on its
  receiver, and one receiver cannot serve both jobs. The bare navigation chain is blind to
  overwrites; a projected receiver runs `select` *before* the sort and window, which defeats the
  fusion and materialises the whole folder; a sorted receiver re-sorts the folder on every write.
  - `ordering: (TList<T>) -> TList<T>` is applied inside `TenshuPagingSource.load` before the
    window — retiring `orderedSource`, a flag that per `core-keyset-paging.md` §6.4 no longer
    distinguishes anything.
  - `invalidation: (TList<T>) -> TList<*>` defaults to the receiver (today's behaviour). ⚠⚠ Drive's
    observes **every mutable value the row renders**, not just the sort key: under a Name sort an
    overwrite leaves `path` alone and changes size and modified time, and `Folder.FileCount` changes
    with every upload into a subfolder — `FileMapper` shows all of them, and today's whole-folder
    sorted stream already observes them. Projected rows diff as entities, so any of those changes
    signals, and a date/size overwrite reorders the listing.
  - ⚠ `PATH` is not optional in that projection: `select` keeps only the selectors it names
    (`SelectImplementation.selectItem`), and a `File`'s `DataId` is derived from its `path` field
    (`FileDataIdProviderImpl`), so a projection without it diffs rows with no identity and the
    observer fails on its first update instead of signalling.
  - Cost: the row projection over the whole folder per change — the same evaluation today's sorted
    stream pays, minus the sort and minus shipping rows (the observer is signal-only). It is not the
    thumbnail projection whose per-change cost melted the 2k-file folder (`TenshuPagingSource`
    KDoc; the listing projection has excluded `File.Preview` since), but it is O(N) plus the diff,
    which is why SP-5 lands before SP-3.
- **B4**: two mechanisms, and neither is enough alone.
  - A per-generation **dedupe** in the paging source, keyed by the row identity the list keys by:
    a head insert makes an append re-serve a row, and the dedupe drops it. It cannot do the
    opposite job — a row that *moved above* the loaded boundary (a head insert, an overwrite under a
    date sort) is not duplicated but missing, and only a rebuild serves it.
    ⚠⚠ The check-and-record must be **atomic**: Paging can run an append and a prepend together
    (`KeysetPagingSource`'s total-count KDoc says so), and after a re-anchor both may read snapshots
    shifted by an intervening write, so two unsynchronised loads can each see a row as unseen and
    both emit it — the duplicate-`path` crash the dedupe exists to stop — or corrupt a plain KMP set.
    A `Mutex` around *filter this page against the set, then record what it kept* is enough, and
    unlike the count it does not serialise the reads: the lock is taken after the rows are in
    memory, so it guards an in-memory pass of ≤ page-size rows.
  - ⚠⚠ A **bounded** reload. Every settled change rebuilds the `Pager` (`PagingUtil.kt:255`), but
    "settled" is a trailing debounce that restarts on every signal (`debounceExceptFirst`:
    `collectLatest` + `delay(400)`). A backup writing faster than every 400 ms therefore gets **no
    rebuild until the whole burst ends** — the drift window is the burst, not 400 ms. So the
    debounce needs a maximum wait (rebuild at least every few seconds while signals keep arriving).
    ⚠ A rebuild mid-scroll resets the presenter under the reader: `debounceExceptFirst`'s KDoc
    records the `IndexOutOfBoundsException` exactly that caused once, so the max-wait rebuild must
    be tested while a scroll is in flight, not only at rest.

## 7. Phases

- **SP-1 — value-property keys fuse on composite rows** (`:core-common`). ✅ 2026-09-15 (§10.1). B2 only; benefits every
  `sortBy(property)` over a composite array, not just Drive. Tests: fusion fires for
  `root.files`-shaped arrays; store-read count per row asserted (the bench's counter), because a row
  assertion passes fused or not. Measure 2c before/after, **time and peak heap** (§4.4: the pass is
  eager, so memory is O(N) thin rows whatever the window).
- **SP-2 — a type-test predicate + `TypeIs` key** (`:core` op + `:core-common` gate). ✅ 2026-09-15 (§10.2). D2 decides its
  spelling. ⚠⚠ A new operation bumps no schema version, so an older Control **admits** the session
  and the call fails later — and because `isA` rides *nested* inside `sortBy`'s selector, it fails
  at **decode**, not at dispatch: E3-1's typed `UnsupportedOperationError` covers only a top-level
  unknown op (`core-schema-evolution.md` §9.3.1, pinned by
  `UnsupportedOperationGateTest.anUnknownTypeInsideAKnownOperationIsNotReportedAsAnUnsupportedOperation`),
  so the user gets an untyped error on every sorted listing. A deliberate
  `TENSHU_STORAGE_FORMAT_VERSION` bump with an empty core migration is the fence (§10.2).
  Tests: the type key orders folders first over **both** row shapes — a composite scan and an
  `asEntity` scan combined with a value-property key (§4.4 kind 2's `Entity.type` trap).
- **SP-3 — Drive's sorted listing pages** (`:drive-shared-logic`, `:core-paging`). ✅ 2026-09-15 (§10.6). Order in the
  chain, `ordering` + `invalidation` parameters (B5), per-generation dedupe and a bounded reload (B4),
  `foldersFirst` removed. Guards: the
  three reactivity tests of `core-paging-scroll-restoration.md` stay green;
  `FilesListingEfficiencyTest` gains a sorted variant asserting fusion; a drift test inserts at the
  head between two page loads and asserts no duplicate `path`; ⚠⚠ an **overwrite test** bumps a
  tail row's modified time past the loaded boundary of a date-descending listing and asserts it is
  served, in its new position, after the reload (B5) — which is also the update-after-baseline that
  catches a projection missing `PATH`; under a **Name** sort, the same overwrite refreshes the row's
  size and date subline, and an upload into a subfolder refreshes its file count; ⚠⚠ a **continuous-write test** keeps inserting at the head
  faster than the debounce and asserts the listing still rebuilds within the maximum wait, and a
  rebuild during an in-flight scroll does not throw; a **concurrent prepend + append** test lets
  both loads read around a head insert and asserts the two pages together carry no `path` twice. ⚠ Depends on SP-4 and SP-5.
- **SP-4 — the `FieldTransform` key** (`:core-common`; §4.4 kind 3). ✅ 2026-09-15 (§10.3). ⚠ **SP-3 depends on it** under
  D4's recommendation: the tie-break sits under *every* sort, so without it either every sorted page
  falls off the fused path or ties turn case-sensitive. Tests: `path.lowercase()` fuses; ties order
  like `name.lowercase()` for siblings, including mixed-case names.
- **SP-5 — `StreamRegistry`'s change diff in O(n)** (`:core-common`). ✅ 2026-09-15 (§10.4). Independent of the rest and
  worth doing on its own; SP-3 needs it, because B5's observer diffs the whole folder on every change.
- **SP-6 — retire the natural-order listing** (`:drive-shared-logic`, `:drive-shared-ui`; D5). ✅ 2026-09-15 (§10.8). The
  default becomes a concrete sort (D6), and `sort == null`, `getFilesPaged(folder)` and the
  `FileSortBar` "Sort" placeholder go, leaving one paged sorted path. ⚠⚠ **Only after SP-3's device
  measurement**: until then a chosen sort is the whole-folder path (§2.1), so moving every user onto
  a sort first would regress exactly the large folders this track exists for. ⚠ The persisted
  `drive.listing.sort` has no `null` to migrate *to*, only *from*: an absent value reads as D6's
  default.

Every phase measures on the device, not only the JVM — §2.2's constants were ~10× off on the phone.

## 8. Decisions

- **D1 — shape.** One ordered list with a type-test key (§4.4) vs two segments (§4.1).
  **Decided 2026-09-15 (maintainer): §4.4.**
- **D2 — spelling of the type test.** A generic core `isA<Folder>()` (any schema, any service; the
  gate recognises one op id) vs a file-service `File.IsFolder` derived property like `File.Category`
  (the gate must special-case a service's property, and dynamic properties are refused today).
  **Decided 2026-09-15 (maintainer): generic `isA<T>()`.** ⚠ A new operation, so SP-2's lockstep
  caveat applies.
- **D3 — drift.** Offsets + per-generation dedupe + a bounded reload (§6) vs a composite keyset
  cursor, which first needs `or`, tuple comparison and string ranges in the predicate vocabulary —
  and would still need the bounded reload for overwrites, which move a row rather than insert one.
  **Decided 2026-09-15 (maintainer): offsets + an atomic per-generation dedupe + a bounded
  (max-wait) reload.**
- **D4 — case in the Name key and the tie-break.** Both matter, because the tie-break runs under every
  sort and backup folders are full of ties. (a) `path.lowercase()` via SP-4 — case-insensitive like
  today and fused; (b) the raw `path` field — fuses with no new key kind, but case-sensitive, so
  "Zebra" sorts before "apple" among ties and under Name; (c) keep `name.lowercase()` — today's
  semantics, never fuses, every sorted page ≈ shape 2a. **Decided 2026-09-15 (maintainer): (a)** —
  case-insensitive stays, so SP-4 is required before SP-3.
- **D5 — the sort menu.** *Decided 2026-09-15 (maintainer).* The question was whether to add a way
  back to natural order, and whether folders stay first under every key (Box, Explorer, DocumentsUI
  — today's behaviour), only under Name (Finder), or behind a toggle (Google Drive web). §4.4
  supports all three: the type key is simply present or absent in the chain. Folders first, for
  these reasons —
  - **Drive is browsed on a phone, through folders that are mostly files.** A camera backup holds
    thousands of rows; mixed into a date sort, a subfolder lands wherever its own timestamp puts it,
    potentially thousands of rows down a touch list. First, it is always one tap away. Finder's
    mixed default fits a desktop column view with a sortable Kind column — a different model.
  - **It is the platform's and the category's default**: Android DocumentsUI always puts directories
    in front, Box's API always sorts by type first, Google Drive web defaults to "On top" (§5).
  - **It keeps SP-3 a performance change, not a behaviour change** — today's listing already does
    exactly this.
  - **A toggle stays additive**: it adds or drops the type key, and both chains fuse (§4.4), so
    nothing here forecloses it. ⚠ A "mixed" mode would first have to define what a *folder's* size
    and modified time mean in a comparison with files — this doc has not established how those two
    evolve after a folder is created — which is a reason to add it on demand, the Google Drive
    per-device "On top / Mixed" shape, rather than speculatively now.
  **Decided: folders always first, and no natural-order entry** — an unsorted listing is a
  store order the user never chose and reads as a bug, and once SP-3 lands the sorted path is fast
  enough to be the only one. That turns "no way back to `null`" from a gap (§2) into the design, and
  retires the natural-order path altogether (SP-6).
- **D6 — the default sort** once natural order is gone. **Decided 2026-09-15 (maintainer): Name
  ascending** (case-insensitive, per D4) — the conventional file-manager default, and the key under
  which folders-first matters most. Weighed and not taken: modified-time descending ("recent
  first"), which suits backup folders but makes a freshly opened folder's order change under the
  user while a backup runs.

## 9. What is not known yet

- Device numbers for any paged sorted shape; §4.4's per-page figure is inferred from shape 7a.
  ⚠ §2.3 now measures the built listing on the JVM and finds the per-page cost is **one full pass
  over the folder**, so §4.4's figure understates a large folder; what is still unmeasured is the
  same scroll on a phone, where per-call constants were ~10× (§2.2).
- Cold-cache cost (after a process restart the store reads through SQLite; the bench's working set
  was resident).
- Drive on the web builds its own listing in TypeScript (`drive-web-parity.md`); nothing here
  reaches it.

## 10. As built — SP-1, SP-2, SP-4, SP-5 (2026-09-15)

All four engine phases in one PR, no Drive change yet: `applySort` still takes the whole-folder
path until SP-3. Decisions D1–D4 were taken as §8 recommended; D5 is untouched. What differs from
§4.4/§7, or was left open there:

### 10.1 SP-1 — a kept property makes the row an entity

§4.4 offered two ways to reach a property on a composite scan — widen the thin read to keep
properties on composite rows, or read the scan `asEntity`. Neither is what shipped, because a
composite has no properties map to widen *into*, and reading through a copied reference
(`reference.copy(isEntity = true)`) would register a second stream listener for the same array.
Instead the rule lives in the mapper: **`DbEntryMapper.deSerialize` reads a row as an entity
whenever its projection keeps properties**, whatever `asEntity` says. The fusion's `isEntity`
refusal is gone; nothing else passes `keepProperties`, so no other read changes shape.
`OrderedWindowFusionTest.propertyKeyOverACompositeArray_fuses_inOneStoreReadPerRow` pins **N + 2
store reads** for an N-row page (the array entry twice, each row once) against the 2N + 2 the
re-read path cost.

### 10.2 SP-2 — `isA<T>()`, generic, carrying the schema id

D2 → generic. `Data.isA<T>()` in `:core` (`IsA.kt`), with an in-memory spelling `isA(schema)` and
the wire spelling `isA(typeId)`; `IsARo` / `IsAImplementation` in `:core-common`; logic in
`isALogic`. The wire carries the **schema id as a string**, resolved and parent-walked on the proxy;
an unknown id is `false`, not an error (Kotlin's `is` on an unrelated class). `filter { isA<Folder>() }`
is therefore the first real folder predicate — `filter<Folder> { … }` only narrows the type.

⚠⚠ The `Entity.type` trap of §4.4 kind 2 is real and is pinned:
`typeTestKey_combinedWithAPropertyKey_readsTheTypeOffTheEntity` combines the type key with a
property key (SP-1 makes that scan read entities) and would interleave folders and files if the
key read `schema`. The subtype check is memoised per distinct row type, so a listing pays two
schema walks, not N.

⚠ **Schema lockstep.** The op is pinned by `SchemaShapeBaseline` (`tenshu/core/protocol/isA`,
regenerated in the same PR) and, as first shipped, bumped no version.

⚠⚠ **The device pass corrected the failure mode, and it is worse than §7 SP-2 assumed.** SP-2 said
an older Control "fails at *dispatch*". It does not: `isA` is nested inside `sortBy`'s selector
callstack, so a proxy that does not know the tag fails **decoding the task**, before any dispatcher
sees an operation to reject. `core-schema-evolution.md` §9.3.1 converts only a **top-level** unknown
tag into a typed `UnsupportedOperationError`, and
`UnsupportedOperationGateTest.anUnknownTypeInsideAKnownOperationIsNotReportedAsAnUnsupportedOperation`
pins that boundary deliberately. So the session is admitted and every sorted listing fails with an
untyped, unnamed error — *every* listing once SP-6 lands.

✅ **Fixed 2026-09-15: `TENSHU_STORAGE_FORMAT_VERSION` 21 → 22**, plus the empty
`schemaMigration(CORE_SERVICE_ID, fromVersion = 21)` that keeps a fence-only bump from wiping every
v21 store (the rule v20's note in `StorageFormat.kt` states). `AnnounceSessionImplementation`
compares the core slot, so a newer Drive against an older Control is now refused at connect time
with the typed "update Control" shape. ⚠ EI-1's cut is unchanged one step lower: v20 and below stay
outside the floor.

### 10.3 SP-4 — `FieldTransform`, by allowlist

A stored, non-optional field followed by ops from `PURE_SCALAR_TRANSFORMS` — one entry,
`Lowercase.ID → lowercaseLogic`, the same function `LowercaseImplementation` dispatches, so the
fused and unfused keys are computed by one body. Ops with parameters are not admitted
(`fieldThroughAnOpWithParams_fallsBack`); adding `substringAfterLast` would mean threading its
params through and is not needed by any caller.

### 10.4 SP-5 — `emitChanges`

`StreamRegistry`'s diff derives each row's `DataId` once and matches both sides through a hash map
(`emitChanges`), instead of computing it inside two nested scans. Emissions are byte-for-byte the
old ones — first-match-wins on a duplicated identity, id-less old rows never reported removed,
id-less new rows still matched by equality — and
`ExecutionContextStreamTest.changeDiff_reportsOnlyTheRowsThatChanged` pins them.

### 10.5 Engine phases — not measured on their own

No re-run of the §2.2 bench for SP-1/2/4/5 in isolation: they change nothing Drive calls until SP-3
moves the sorted listing onto the paged path, so the numbers to take are SP-3's (§10.7).

### 10.6 SP-3 — the sorted listing pages (2026-09-15, second PR)

Built as §6 describes, with these as-built notes:

- **`:core-paging`.** `TenshuPagingSource` takes `ordering` (applied in `load` ahead of the window)
  and `rowKey` (the per-generation dedupe, a `Mutex`-guarded set per source instance — Paging may
  run an append and a prepend together); `pagingSource` / `toPagingData` take those plus
  `invalidation`, the chain `reloadSignals()` observes. All three default to today's behaviour, so
  Control's and Fit's calls change only by losing the flag. **`orderedSource` is gone**: it never
  distinguished anything (`core-keyset-paging.md` §6.3) and `ordering` is what does. Fit's KDoc and
  the three docs that called the flag "required" carry a correction each.
- **The bounded reload** is two timers, not a clock: the trailing one is cancelled and relaunched
  per signal (the old `collectLatest` + `delay`), the ceiling one is launched once per burst and
  fires after `INVALIDATION_MAX_WAIT_MS = 3 s` regardless. Whichever fires first emits; the other
  finds the epoch moved and does nothing. Two timers so virtual time in tests drives it exactly as
  wall time does.
- **Drive.** `listingOrder(sort)` is `sortByDescending { isA<Folder>() }`, then the key, then
  `path.lowercase()` (the Name key *is* the last). One `LISTING_PAGING_CONFIG` and one
  `listingRow()` projection serve both overloads; the sorted one adds `ordering`, `invalidation =
  { it.select { listingRow() } }` and `rowKey = { it.path }`. `foldersFirst`,
  `WHOLE_FOLDER_LOAD_STATES` and `applySort` are deleted, with `FoldersFirstTest`.
- ⚠ **The natural-order listing still observes the bare receiver.** §3 B5 noted (inferred, not
  tested) that it therefore misses in-place overwrites — a stale size/date subline until some other
  write lands. Left as is on purpose: D5 retires that path altogether (SP-6, after the device pass),
  which removes this class of staleness for free, and switching it to the projected observer in the
  meantime would cost the projection over the whole folder per change on the path every folder
  opens on today. ⚠ What SP-6 inherits: the sorted scroll costs ~3× the natural one in store reads
  (2 149 vs ~690 at 5 windows on the JVM — the projected observer pass plus one thin pass per
  window), so the device pass (§10.7) is the number that says whether every user can be moved onto
  a sort, which is exactly why D5 gated SP-6 on it. ✅ It cleared: 1.1 s first paint, flat in folder
  size.
- ✅ **The lockstep trap is fenced** (§10.2), which it was not when SP-3 first landed: a Drive with
  this change against an older Control fails the sorted listing at **decode**, so the storage-format
  bump refuses the session at connect time with the typed "update Control" shape. There is no
  fallback to the whole-folder path — that path is gone — which is why the fence, not a fallback,
  is the answer.
- **Tests** (`SortedFolderPagingIntegrationTest`, seven cases, over the real in-process proxy): B1
  folders lead across a page boundary under a date sort; B3 a case-insensitive Name sort with
  folders first; B5 an in-place `ReplaceFile` reorders a live date listing, moves a row from the
  **unloaded tail** of a 45-row listing to the top of the loaded window (the §7 shape: nothing
  duplicated, a row missing until the reload serves it), refreshes the subline under a Name sort
  (the change that moves nothing), and an upload into a subfolder refreshes the folder row's count
  (the parent's own array is untouched — only an observer that resolved `Folder.FileCount`, and so
  registered the subfolder's array, can see it); B4 a 4 s burst of writes every 100 ms while the
  reader pages forward through the presenter — completes without throwing, rebuilds while the
  burst is still running, and serves no row twice. ⚠ The in-flight scroll is the presenter's
  `get`, the shape both screens use, not `asSnapshot`'s `scrollTo`: two observers of one chain in
  one session collide on a stream id (below), so the burst test keeps to one collector. `TenshuPagingSourceTest` pins the four mechanisms in
  isolation (ordering before the window, dedupe under a head insert, the observed chain, the
  ceiling under ten seconds of continuous signals, and the concurrent prepend + append around a
  head insert D3 asks for — the pages together carry no row twice, and the row pushed *between*
  the two windows is in neither, the "missing, not duplicated" half only the reload serves).
  `FilesListingEfficiencyTest` gains the sorted
  scroll with its store-read bound: **2 149** reads at 5 windows, against 4 263 before the B6 fold
  (the natural scroll is ~690). The three reactivity guards of
  `core-paging-scroll-restoration.md` are untouched and green.
- ⚠ **Found on the way, not fixed here: two identical observers in one session share a stream
  id.** The burst test's first draft ran a timing collector beside a snapshot of the same chain and
  the signals stopped the moment the snapshot closed — `createStreamId` hashes the callstack, so
  identical chains collide in `StreamRegistry` and one collector's close removes the other's
  registration. Recorded in `tech-debt.md` ("Two identical observers in one session share a stream
  id"); it is a production shape too (Files + move picker on one folder).

### 10.7 The device pass (2026-09-15) — and the four things it found

OnePlus CPH2581, Drive and Control both built from this stack, schema lockstep asserted.

| check | before | after |
|---|---|---|
| open `Camera` (618 rows), "Date modified" ↓ | 2.8 s | **1.1 s** (×4) |
| open `WhatsApp Images` (830 rows) | 3.0 s | **1.1 s** (×2) |
| full scroll of `Camera`, 126 screens stitched | — | 618 rows in exactly the expected order, no duplicate, no gap |
| Name ↑ / ↓ on a mixed folder | — | folders first in both, case-insensitive, both groups reversed |

⭐ **First paint stopped scaling with the folder** — the same 1.1 s at 618 and 830 rows, against a
whole-folder path that grew with every row. That is the number D5 gated SP-6 on, and it clears it.

⚠ Not exercised: a real backup into the open folder (the realm was near its quota), and an in-place
overwrite of a listed file. Both are covered by desktop integration tests, neither by a phone.

**Four findings, all fixed here except the last:**

1. ⚠⚠ **The version fence was missing, and the failure mode was worse than SP-2 assumed** — see
   §10.2. Fixed: `TENSHU_STORAGE_FORMAT_VERSION` 21 → 22 with an empty core migration.
2. ⚠⚠ **A new row landed *above* the viewport.** A `LazyColumn` keyed by `path` anchors on the row
   it is showing, so a head insert leaves the new row off-screen — a folder created in an open
   "Date modified ↓" listing sat one row above the fold for 90 s until a swipe revealed it. Natural
   order never showed this because uploads append at the tail; under "newest first" it is every
   created folder and every photo a backup adds. Fixed in `PagedFilesList`: when the viewport sits
   exactly on the row that used to be first, at offset 0 — the shape only a reader parked at the
   top produces — follow the insert back to 0. A reader who has scrolled is left alone, since
   losing their place is the worse bug (`shouldFollowHeadInsert`, seven cases).
3. ⚠ **Page loads ran on the caller's thread** (found by review, not the device). Paging calls
   `load` in the collector's context, which is `Dispatchers.Main` under Compose and
   `Main.immediate` on K/N; where the proxy is in-process the ordered scan is real work, and the
   whole-folder path it replaced had a `flowOn(Dispatchers.Default)` that was silently dropped.
   Fixed: `load` runs in `withContext(Dispatchers.Default)`.
4. **The projected observer is now the dominant per-open cost** — ~18 % of Control's samples
   (≈1.1 s CPU) at 618 rows, against 3.4 % for every fused page load combined, and O(N) per write
   during a backup. Accepted by §6 B5 and not a blocker, but it is the obvious next engine slice: a
   signal-only stream could diff stored-row hashes instead of evaluating a projection, which would
   make the bare receiver property-aware for free and retire the `invalidation` hook. Recorded in
   `tech-debt.md`, not fixed here.

⚖️ **Still owed**, and it is SP-6's gate rather than SP-3's: a backup running into an open folder,
to see whether the ~3 s ceiling rebuild reads as a flicker. The JVM bench of §2.2 was not re-run for
shape 5 with `isA<Folder>()`; the store-read counts in `FilesListingEfficiencyTest` cover the same
question at lower resolution.

### 10.8 SP-6 — natural order is gone (2026-09-15)

D5 gated this on SP-3's device measurement, and §10.7 cleared it: 1.1 s to first paint, flat in
folder size. So the second listing mode goes.

- **One method.** `FilesRepository.getFilesPaged(folderPath, sort = FileSort.Default)` — the
  unsorted overload and `TenshuFilesRepository`'s natural-order body are deleted. Callers that
  passed no sort (the move picker, a dozen tests) keep compiling and now get a sorted listing,
  which for a folder picker is strictly better.
- **Nothing is nullable.** `DisplayPreferences.observeSort()`/`setSort`, `FilesScreenViewModel.sort`,
  `FileSortBar`, `FilesListingToolbar`, both screens and the iOS `FilesView` all take a real
  `FileSort`. The sort chip always names a key and a direction; iOS always shows Order.
- **The migration is the decoder.** `drive.listing.sort` keeps `""` as its stored default, which
  now decodes to `FileSort.Default` (Name ascending, D6) instead of `null`. A device that chose a
  sort keeps it; one that never chose, or stored `""`, lands on the default it would have been
  given. No rewrite, no new preference.
- ⚠⚠ **The accepted cost, measured.** The default listing went from ~690 store reads for an
  open-and-4-window scroll to **2123** — ~3×, pinned in `FilesListingEfficiencyTest`, whose bound
  and comment now describe the sorted shape. Most of it is the projected change observer (§10.7
  finding 4): natural order observed the bare receiver, which is cheap precisely because it is
  blind to in-place overwrites. This is the trade D5 made knowingly, and the device number is the
  one that matters — 1.1 s, flat.
- ⚠ **The iOS Swift is unverified.** `drive-ios/Sources/FilesView.swift` was updated for the
  non-optional sort (bindings, picker tags, the `@Published` seed), but no gate in this
  environment compiles Swift — only `scripts/ios-smoke.sh`, which is macOS-only. It needs a build
  on a Mac before it can be called done.

## Appendix — reproducing §2.2

A throwaway `jvmTest` in `:file-service-proxy` (not committed): `/Backup/Phone/Camera` with N
1-byte files plus 5 subfolders, `sourceModifiedAt` scrambled, the real `navigateTo` chain
(`search<Folder> { path equalTo … }.ifEmptyError().files` per level) and Drive's `select` projection;
each shape one probe run, two warm-ups, median of five `getAll()`. Store reads counted by an
`ObjectStore` wrapper around `getObject`; fusion by five temporary counters on the
`EvaluateCallstack` peephole branches. Run with `BENCH_N=10000 [BENCH_STORE=sqlite]`.
