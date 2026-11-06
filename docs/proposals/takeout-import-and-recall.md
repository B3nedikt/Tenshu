# Takeout import & recall — Track B

**Status:** v1.0 — **B1–B5 all shipped** (2026-08-12; as built §6–§10). ⚠ The track's remaining debt is a **device pass**, not code. Companion to
[`timeline-ingestion.md`](timeline-ingestion.md) (which owns the parser and the write path),
[`mia-query-layer.md`](mia-query-layer.md) (which owns the grammar) and
[`timeline-photos.md`](timeline-photos.md) (which owns a photo's coordinate).

Track B is the second of the four tracks the 2026-08-11 roadmap conversation split out. Track A
(photos by place) shipped as `mia-query-layer.md` §12. This one is about **the data a user already
has**: getting a Google Takeout export in, and being able to *ask about it afterwards*.

---

## 1. ⚠⚠ Start by correcting the premise

The track was scoped as *"at the moment we only import Takeout data for our Timeline app, but the
export also contains the location history"*. That is not what the code says, and the difference
changes what Track B is:

**Location history is exactly what we already import.** `GoogleTimelineParser` handles both
vintages — the 2024+ on-device export (`semanticSegments`) and the legacy Takeout
*Semantic Location History* (`timelineObjects`) — and turns visits into places + moments and
activity segments into movements with their traces.

So Track B is not "parse the location history". It is the four things standing between that parser
and a user who actually gets value from it. All four were verified against the tree at `4d0afc03`:

| # | Finding | Evidence |
|---|---|---|
| B-i | **Import is desktop-only.** `FileTimelineImporter` has exactly **one** production caller. A user whose export is on their phone cannot import at all. | `timeline-desktop/.../Main.kt:188` is the only non-test reference |
| B-ii | **One file per dialog.** `importFiles` takes a list; the only UI that calls it opens `FileDialog.LOAD` and reads `dialog.file` — a single name. A legacy Takeout is one JSON *per month*, so a decade of history is ~120 dialogs. | `Main.kt` `pickAndImport` |
| B-iii | **No zip support anywhere in the product.** `timeline-ingestion.md` §8 deferred it "until demand shows". The demand is the shape of the export itself. | `TimelineImportFile` KDoc; no `ZipInputStream` outside `:vector-tile`'s pmtiles reader |
| B-iv | **The Timeline app has no empty state.** A user with no data sees a blank `LazyColumn` — no explanation, and no route to the importer that exists on another platform. | `TimelineHomeScreen.kt` — `isEmpty` appears once, for bound trips |
| B-v | **Mia answers recall questions with a file-name search.** Five of six natural recall utterances resolve to `FindFilesNamed`. | measured, below |

### 1.1 B-v, measured

Run against `QueryPlanner` as it stands today:

| Utterance | Plan today |
|---|---|
| "what did I do two weeks ago" | `FindFilesNamed("two ago")` ⚠⚠ |
| "when was I last in paris" | `FindFilesNamed("when paris")` ⚠⚠ |
| "what did I do in paris" | `FindFilesNamed("paris")` ⚠⚠ |
| "when did I last visit the gym" | `FindFilesNamed("when gym")` ⚠⚠ |
| "show me my trips to paris" | `FindFilesNamed("trips paris")` ⚠⚠ |
| "where have I been last month" | `RecallMoments(June 2026)` ✅ |

This is the **confidently-wrong** failure MQ-D4 exists to prevent, and it is worse than a refusal:
the user asked about their life and got a filename grep, with an explanation that reads as though
Mia understood. Two gaps produce all five: the time grammar has no *"N units ago"*, and there is no
place-anchored recall intent at all. The last-resort `text != null -> findFilesNamed(text)` arm then
swallows whatever is left.

---

## 2. Decisions

### TB-D1 — ⭐ The unit of import is the **zip**, not a file

`timeline-ingestion.md` §8 said in-app unzip would be added "if demand shows". It has: the export
Google hands the user is a zip, and for the legacy vintage its contents are one JSON per month.
Asking the user to unzip and multi-select is asking them to do the import by hand.

**Shape:** a pure central-directory + local-header walk in `commonMain`, with the raw-deflate call
**injected** rather than `expect`/`actual`. That is the precedent this codebase already set for
compression (`TileDecompressor` in `:map-region-service-proxy`, with `java.util.zip` halves in
`control-shared-proxy/{androidMain,desktopMain}`), and the reason given there holds here: a JS
actual is awkward, and an injected lambda keeps the parsing module dependency-free and testable.

⚠ **Streaming, not `ByteArray`.** Today every seam is bytes-in-memory (`TimelineImportFile(name,
bytes)`). Location-history JSON is fine that way; a Google Photos export is tens of gigabytes and is
not. The zip reader must yield entries lazily and the photo path must consume them one at a time.

### TB-D2 — Route entries by path, but ⚠⚠ **never trust the path**

A Takeout zip has a stable *structure* (`Takeout/Location History (Timeline)/…`,
`Takeout/Google Photos/<album>/…`), so routing an entry to a parser by path is the cheap first cut.

⚠⚠ **Google localises the folder names to the account's language** — a German export says
`Standortverlauf`, not `Location History`. A path-only router silently imports **nothing** from
every non-English export, and reports success. So the path is a *hint* that picks the likely parser;
the parser still sniffs the content, exactly as `GoogleTimelineParser` already does, and an entry
that matches no parser is **counted and reported**, never silently dropped. "Understood 0 of 412
files" is a bug report the user can send us; a blank timeline is not.

### TB-D3 — Import belongs on the phone, and it is one screen in all three apps

B-i is the largest single gap in the track: the export arrives by email or lands in Drive on a
phone, and that is where people will try to open it. Android gets a SAF picker for `*.zip`;
desktop's existing dialog widens to the zip; iOS follows via the document picker.

⚠ The import runs inside the existing bulk-write task lane (C4 — `FileTimelineImporter` already
wraps its writer in `withBulkWriteTask`), so a decade of history rides the relay as batched deltas
rather than a `SyncBlock` per row. Nothing about that changes; it just gains a caller.

### TB-D4 — ⚠⚠ For a Takeout photo, the **sidecar** is the authority, not the EXIF

Google Photos exports write the metadata *beside* the image: `IMG_1234.jpg` plus
`IMG_1234.jpg.json` carrying `photoTakenTime.timestamp` and `geoData.latitude/longitude`. The
image's own EXIF is frequently reduced or absent in the export.

Today `UploadFileImplementation` reads the coordinate out of the bytes
(`captureLocation ?: readExifLocation(data)`), and **`captureLocation` has no caller at all** —
not a client, not even an op parameter: `UploadFile.Schema` declares `parentPath`, `fileName`,
`data` and `sourceModifiedAt`, and nothing else. Its KDoc describes an Android client-side path that
was designed and never wired (what actually made Android work was `setRequireOriginal` handing the
proxy *unredacted bytes* — `timeline-photos.md` §11.2).

So Track B is the first real caller for it, and it needs the op parameter that was never added.

⚠ That is a schema change to a shipped operation, not a new op — unlike Track A's `PhotosNear`.
It owes HR #9's treatment: check `SchemaShapeBaseline`, bump `FileService` if the shape moves, ship
the migration in the same PR.

⭐ The payoff is disproportionate: a Takeout photo import gives Track A's "pictures taken in Munich"
a corpus **years deep** on day one, for a user who never backed a photo up from a Tenshu device.

### TB-D5 — Recall gets two grammar shapes, and one refusal

1. **"N units ago"** — `two weeks ago`, `3 days ago`, `a month ago`. A window, not a point: "two
   weeks ago" means *that week*, not that instant, and answering with a single day is the kind of
   precision that reads as a bug.
2. **Place-anchored recall** — "what did I do in Paris" (moments at a resolved place) and "when was
   I last in Paris" (the most recent one). This reuses Track A's machinery exactly: stage one
   resolves the name against `Place` rows, stage two reads moments — the join stays in the planner.

⚠⚠ **And the last-resort file-name arm must stop swallowing recall questions.** A question that
opens with "what did I do" / "when was I" / "where was I" is *about the user's life*; if the grammar
cannot resolve it, the honest answer is a refusal that says what it did not understand. Falling
through to a filename grep is how "when was I last in Paris" became a search for `when paris`.

⚠ "when was I last in X" also has a shape no current plan has: **one row, chosen by recency**, not a
list. `sortBy(key).take(1)` is exactly the ordered-window fusion F1 already ships.

### TB-D6 — The Timeline-only user is a first-class user, and the empty state is the import

B-iv is not cosmetic. Timeline's whole premise for someone who has not enabled capture — or who
cannot, on desktop — is *"bring your history"*. The empty state is therefore not an apology; it is
the primary call to action, and it must not read as "capture is broken".

⚠ Once history is imported, a *second* empty state exists and means something different: "you have
history, but nothing on this day". Conflating the two ("no data yet") tells a user with ten years of
imported timeline that they have nothing.

### TB-D7 — What we still do **not** import, and why

- **`Records.json` raw pings** — `timeline-ingestion.md` §8 dropped these and the reason stands:
  dense polylines, no narrative value, and a per-day row count that dwarfs everything else in the
  store. ⚠ Revisit only with the content plane, never as ordinary rows.
- **`transitPath`** (line name, ordered stops) and **saved places / reviews / ratings** — Google
  *content* rather than the user's own record of where they were, blocked by the same ToS line
  §5 draws. ⚠ The distinction that matters: a coordinate the user's phone recorded is the user's;
  Google's name for the bus line is Google's.
- **YouTube / Chrome / Search history** — in the export, and deliberately out of scope. We have no
  data model that wants it, and importing it would make the realm hold a browsing history to no
  end. ⚠ The privacy-preserving choice and the cheap choice agree here; take it.
- **Google Fit / Calendar / Contacts entries in the same zip** — real candidates, but they belong to
  those apps' own import paths (Fit's `ActivityFileParser`, Calendar's `.ics` opener, Contacts'
  vCard). Track B routes them **only** if TB-D2's router makes it nearly free; otherwise they wait.

### TB-D8 — Idempotency is already solved; do not re-solve it

Every imported entity is content-addressed off the export's own invariants (`placeId`/coordinates
for a place, `(placeRef, startMs)` for a visit, `startMs` for an activity), so re-importing the same
zip folds to the same rows. Photos get the same treatment for free — content-addressed bytes
(`file-content-addressing.md` Ph0–3) mean re-importing an unchanged photo moves no bytes.

⚠ This is what makes a *partial* import safe, which is what makes it acceptable for a big zip to
fail halfway. No resume bookkeeping is needed: re-run it.

---

## 3. Phase map

| Phase | What | Depends on |
|---|---|---|
| **B1** ✅ | The zip reader + entry router: `commonMain` central-directory walk, injected inflate, path-hint-plus-sniff routing, an import summary that counts what it did not understand (TB-D1, TB-D2) | — |
| **B2** ✅ | Import on Android (SAF `*.zip`), desktop widened to zip; one shared import surface + progress (TB-D3) | B1 |
| **B3** ✅ | Timeline-only UI: the empty state as the import entry point, and the two distinct empties (TB-D6) | B2 |
| **B4** ✅ | Mia recall: "N units ago", place-anchored recall, "when was I last in X", and the refusal that replaces the filename fallback (TB-D5) | — (independent) |
| **B5** ✅ | Google Photos from the zip: sidecar-authoritative capture time + coordinate, the `UploadFile` op parameter, streaming (TB-D4) | B1 + a schema bump |

⭐ **B4 is independent of the import work** and is the cheapest user-visible win in the track — it
fixes five confidently-wrong answers with grammar, no new storage and no new op except the recency
read. It can go first, or in parallel.

⚠ **B5 is the expensive one** and the only one that touches a shipped wire contract. Do not fold it
into B1 to "save a PR"; the zip reader wants to be boring and proven before tens of gigabytes of
photos ride on it.

---

## 4. Open questions

- **How large is realistic?** Nothing here is measured. A 40 GB photo export at one upload per photo
  is a different program from a 200 MB location history, and the answer decides whether B5 needs a
  resumable queue rather than TB-D8's "just re-run it".
- **Where does the zip live while it is read?** Android hands a SAF `Uri`, not a path; the reader
  must seek (a zip's directory is at the *end*), and a content stream is not seekable. Copy to cache
  first, or require a file path? This is the first thing B1 has to answer.
- **What does an import summary say?** "412 files, 380 understood" invites the question "which 32?".
- **Does any of this belong to Control rather than each app?** Three apps each growing an import
  screen is the shape we have; a single "import your data" surface in Control is the shape a user
  would expect. ⚖️ Product call, not a technical one.
- **iOS.** B2's picker and B1's inflate both need an iOS half, and no `TileDecompressor` actual
  exists there today — so iOS has no decompression seam at all yet.

---

## 5. Version

Track B v0.1 · 2026-08-12 · facts verified against `4d0afc03`. No code.

---

## 6. As built — B4: recall (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:mia-service` + `:mia-service-proxy`. No schema change, no
new op, no wire delta — B4 is grammar and one chain.

The five confidently-wrong answers §1.1 measured are gone. What replaced them:

| Utterance | Before | After |
|---|---|---|
| "what did I do two weeks ago" | `FindFilesNamed("two ago")` | `RecallMoments(29 Jun – 6 Jul)` |
| "when was I last in paris" | `FindFilesNamed("when paris")` | `RecallAtPlace("paris", latestOnly)` |
| "what did I do in paris" | `FindFilesNamed("paris")` | `RecallAtPlace("paris")` |
| "when did I last visit the gym" | `FindFilesNamed("when gym")` | `RecallAtPlace("gym", latestOnly)` |
| "show me my trips to paris" | `FindFilesNamed("trips paris")` | `RecallAtPlace("paris")` |

### 6.1 "N units ago" is a window, and the count is part of the time expression

`two weeks ago` snaps to that **ISO week**, `3 days ago` to that day, `a month ago` to that
calendar month. ⚠⚠ A window, never an instant: nobody counting backwards in weeks means a
particular Tuesday, and answering with one day is false precision that reads as a bug.

⚠⚠ The half of this that actually caused the measured bug is `isTimeWord`: **the count belongs to
the time expression**. With "two" left behind, the leftover was `two ago` and the planner did the
only thing it could with a leftover — searched filenames for it. Number words (up to twelve) and
bare digits are now time words.

### 6.2 ⭐⭐ The first nested-field predicate in the codebase

`RecallAtPlace` reads moments by `filter<Moment> { subject.id equalTo placeId }` — reaching through
the `SubjectRef` composite a moment carries. Every other predicate in the tree, Mia's or otherwise,
filters a **top-level** field; nothing had asked the chain DSL to do this before.

It builds and executes correctly (probed before it was relied on, now pinned by
`momentsAtAPlaceReadThroughTheNestedSubjectField`). That is a genuinely useful fact about the DSL
beyond this feature: a composite field's sub-fields are reachable in a predicate.

⚠ Ordering rides in the chain (`sortByDescending(timestamp).take(n)`), not in Kotlin, so the
"latest" case runs as ordered-window fusion's single bounded pass (F1) instead of materialising
every visit the user ever made to that place.

### 6.3 The join is Track A's, generalised

Stage one resolves the name against `Place` rows, stage two reads that place's moments — the same
two-stage shape as the photo join, and `AskMiaImplementation.placeJoin` is now shared by both.

⚠⚠ **Recency is re-established across places.** Each place's chain returns its own moments
newest-first, but "when was I *last* there" has one true answer across all of them, so the merged
list is re-sorted by time before the limit bites. Taking the first row of a concatenation would
answer about whichever place resolved first — not a fact about the user's life. (This is also why
the early-exit at `limit` only applies to the non-recency path.)

⚠ Stage two is validated with the same policy, as in Track A. `EqualTo.ID` and `SortBy.ID` join the
operation allowlist — both reads.

### 6.4 ⚠ The refusal that replaced the fallback

A recall opener ("when", "did", "trip(s)", "been", "visited", plus "last"/"latest"/"recently")
makes the utterance a question **about the user's life**. If neither a time nor a place resolves,
Mia now says *"I can look back over your timeline by time or by place"* instead of falling through
to `findFilesNamed`.

⚠ The recall vocabulary is also stripped from the *name* being searched for — the difference
between `RecallAtPlace("gym")` and `RecallAtPlace("when gym")`. Routing reads the raw utterance;
the words do not survive into the text.

⚠ A photo word still wins: "show me my photos from paris" is a question about pictures that happens
to name a place, and it goes to Track A's join. Pinned.

### 6.5 What is proven

- **28 planner tests** (18 before): every window shape, both recall intents, the refusal, and the
  ordering guard that keeps the photo intent ahead of recall.
- **9 end-to-end tests** (`AskMiaRecallTest`): utterance → plan → two chains → citations, including
  the nested-predicate read, the one-row recency answer, recency **across** two matching places,
  the unknown-place answer, and the regression guard that a recall question never becomes a file
  search.

⚠ **Not proven:** anything about how a Timeline built from an *imported* Takeout answers these —
that is B1–B3, and until then recall reads whatever live capture recorded.

### 6.6 ⚠ Still open in B4

- **"How often was I at X"** — counting, which no plan shape does yet.
- **A place with no `Place` row** (a city you passed through but never dwelt in) is unknown to
  recall for the same reason it is unknown to Track A's photo join.
- Localisation: `"2 weeks ago"` is assembled in English, the same ADR-0016 debt MQ-D4 flags for the
  grammar as a whole.

---

## 7. As built — B1: the archive is the unit of import (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** new module `:takeout-import`, plus `FileTimelineImporter` and
the desktop picker. No schema change, no wire delta.

One pick, and every monthly export inside the zip lands — instead of unzipping a decade of history
and choosing the files by hand.

### 7.1 A new module, and why the zip reader is in it

`:takeout-import` holds two halves that will separate later:

- `zip/ZipArchive` — a **generic** ZIP reader (central directory, STORED + DEFLATE, zip64).
  Nothing about it is Takeout-specific; it moves out the day a second consumer wants it.
- `TakeoutRouter` — which entry is a timeline export, which is a photo, which is a photo's sidecar.

⚠ The inflate is **injected**, not `expect`/`actual` (TB-D1) — the `TileDecompressor` precedent.
`jvmInflate` is the JVM/Android half; ⚠⚠ it builds `Inflater(true)`, because a ZIP entry is a *raw*
DEFLATE stream: an inflater without `nowrap` fails on the first byte of every entry and looks
exactly like a corrupt archive.

### 7.2 ⚠⚠ The central directory, never the local headers

A local header may legitimately carry **zeros** for both sizes and defer them to a data descriptor
*after* the compressed bytes (general-purpose bit 3) — which is what a streaming writer emits, and
Google's exporter is one. A reader that trusts local headers reads empty files from those archives,
silently. Sizes therefore come from the central directory, and the local header is consulted only
for the name/extra lengths, which describe bytes that are physically there.

⚠ **Zip64 is in from the start**, not deferred: a photo export passes 65 535 entries long before it
passes 4 GB, and *both* write `0xFFFF…` sentinels into the classic record. Treating the sentinel as
a count reads 65 535 of 200 000 photos and reports success. ⚠ The zip64 *extra field* packs only
the fields that overflowed, in order and with no placeholders, so reading it positionally is the
classic bug — which field sits where depends on which sentinels were present.

⚠ Opening costs the tail window plus the directory, and never an entry's payload — pinned by a test
that counts bytes read. That is what makes a 40 GB export openable at all.

### 7.3 ⚠⚠ The path is a hint; the content decides

`TakeoutRouter` classifies by extension first and by **content** for JSON — the two markers
`GoogleTimelineParser` itself sniffs (`"semanticSegments"` / `"timelineObjects"`), so the router and
the parser agree on what a timeline file is by construction rather than by coincidence.

This is TB-D2's trap, closed: Google localises the folder names, and a path-matching router imports
**nothing** from `Takeout/Standortverlauf/…` *and reports success*. Pinned by a German-export test
at both levels.

⚠ A photo is never sniffed — the extension already said so, and an export is thousands of photos
beside a handful of JSONs.

⚠ Each candidate JSON is read **once**: a deflated entry cannot be partially inflated, so the sniff
already costs the whole file, and reading it again to parse would double the work on the largest
files in the archive.

### 7.4 What the summary says

`TakeoutImportResult` reports `imported`, `total`, `understood`, `timelineFiles` and a **sample of
what was not understood** — not a count alone, because the first question anyone asks about "32
files skipped" is *which ones*. "Understood 0 of 412" is a bug report a user can send us; a blank
timeline is not.

⚠ Photos and their sidecars are **classified but not read**: they count toward `understood`, so the
summary is honest, and their bytes never come through a path built for JSON. They are B5's.

### 7.5 What is proven

- **8 reader tests** (`commonTest`, hand-built archives so every byte is visible): directory read,
  zeroed local sizes, zip64 sentinels, directories-are-not-entries, not-a-zip, truncation,
  unknown compression method, and the bounded-open byte count.
- **8 router tests**: content-over-path, the German export, both vintages, photos vs sidecars
  (incl. `.supplemental-metadata.json`), photos never sniffed, the unmatched sample and its bound.
- **2 real-archive tests** (`jvmTest`, against `java.util.zip`'s own output) — ⚠⚠ the hand-built
  fixtures prove the reader survives the *shapes*, and a parser tested only against its own writer
  agrees with itself.
- **6 end-to-end tests** (`timeline-shared-data` desktopTest, real proxy + real DEFLATE): every
  monthly file in one pick, the German archive, the honest summary, photos seen-not-read,
  re-import idempotence, and not-an-archive.

### 7.6 Desktop takes the zip now; the phone is still B2

The existing desktop dialog accepts `.zip` alongside the single JSON, so B1 is usable today. ⚠ It
reads the archive into memory — fine for a location-history export, wrong for a photo export, and
B5 owes a file-backed `ZipSource` before it reads one.

⚠⚠ **The phone still cannot import at all** (B-i). That is B2, and it is the largest remaining gap
in the track — along with ⚠ Android's `content://` stream not being seekable, which is why B2 has
to copy a picked export into cache before this reader can open it.

---

## 8. As built — B2: the phone can import (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:takeout-import` (a streaming archive), `timeline-shared-data`,
`timeline-shared-ui`, `timeline-android`, `timeline-desktop`. No schema change, no wire delta.

B-i is closed: **a phone can import a Takeout export.** Until now `FileTimelineImporter` had one
caller and it was a desktop file dialog, so the platform where the export actually lands could do
nothing with it.

### 8.1 ⭐⭐ Fit had already solved this, and the answer was not the reader B1 built

B1's seekable reader assumed the archive is a *file*. Android hands out a `content://` stream that
**cannot seek**, and a ZIP's index is at the end — so the seekable path would have meant copying a
multi-gigabyte export into cache before reading a byte of it, which §4 had flagged as B2's first
open question.

Fit answered it a year earlier: `JvmArchiveReader` (and `IosArchiveReader`, zlib via cinterop) walk
a Takeout archive **front to back**, one member in memory at a time, deciding by name before
reading. So B2 did not invent a third thing — `StreamingTakeoutArchive` is that shape, with Fit's
two caps (a per-entry ceiling, an entry count) which were learned on a real 10-year export.

⚠ The two readers stayed separate only because Fit's yields *activity files* and this yields raw
entries. A third consumer should merge them.

### 8.2 One seam, two readers behind it

`TakeoutArchive` is a one-pass walk — `walk(wanted, consume)` — and **the name decides before the
bytes are read**. `StreamingTakeoutArchive` implements it over `ZipInputStream`;
`ZipArchive.asTakeoutArchive()` implements it over the seekable reader.

⚠ The seekable one is deliberately *narrowed* to the same shape even though it can do more (it
knows every name before reading anything): the streaming reader cannot do better, and two import
paths is how a phone quietly imports something different from a desktop. Pinned by a test that runs
the same archive through both.

⭐ The routing split that makes streaming possible: `TakeoutRouter.kindOfName` returns `null` for
"only the content can say" — which in practice is *the JSONs alone*. A photo is settled by its
extension and never inflated, pinned by a test asserting a 50 KB photo delivers no bytes.

### 8.3 The Import row, and where it is not

`TimelineImportControl` is a DI-bound interface with a status `StateFlow`, mirroring Fit's
`HealthFilePicker` — including *why* it is DI-bound rather than `expect`/`actual`: only a shell owns
an `Activity` or a window, and **a platform that cannot pick a file does not bind it**, so the row
is absent rather than present and inert. Android and desktop both bind one; iOS does not yet.

⚠ Android's control is a *holder* keyed by owner (Fit's lesson): a dying Activity must not clear a
newer one's launcher and leave the row silently doing nothing.

⚠ The SAF MIME list is broad — `application/zip`, `application/x-zip-compressed`,
`application/json`, `application/octet-stream` — because document providers disagree about what a
zip is, and Drive labels anything it did not recognise as `octet-stream`. A narrow filter greys out
the very file the user came for.

⚠ Both shells resolve **one** `FileTimelineImporter` binding, so phone and desktop import through
the same object, and `TakeoutImportResult.summary()` is shared so they say the same thing.

### 8.4 ⚠ Four outcomes, not two

`summary()` distinguishes: empty archive · read it and recognised *no* timeline file (with example
names) · read timeline files that held nothing new · imported N. And `Failed` is separate again —
"we could not read it" versus "we read it and it had no timeline" send a user to two different
fixes, and collapsing them tells them to re-export when the real answer is "you ticked the wrong
box in Takeout".

### 8.5 What is proven

- **8 end-to-end tests** in `timeline-shared-data`, now including *the same archive through both
  readers* and the photo-bytes-never-read guard.
- Full gate green; **Android smoke gate PASSED**.

⚠ **Not proven — and this is the real gap:** nobody has picked a real Takeout export on a real
phone. The SAF grant, the provider's MIME label, a multi-gigabyte stream and the memory the parse
holds are all device behaviour no unit test sees. ⚠⚠ In particular `importFiles` still takes
**every** timeline file's bytes at once (a decade of monthly JSONs), which is the next thing to
measure and, if it bites, the next thing to fix — B5 cannot ship over it either way.

---

## 9. As built — B3: the Timeline-only user (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `timeline-shared-logic`, `timeline-shared-ui`. No schema change,
no new dependency, no platform code.

B-iv is closed. A user with no data used to see a blank `LazyColumn`: no explanation, and no route
to the importer that existed on another platform.

### 9.1 ⚠⚠ Three states, not two — and the third is the bug

Every list in `TimelineHomeUiState` starts empty, so a screen deciding from `days.isEmpty()` alone
tells a user with ten years of history that their timeline is empty **for as long as the first read
takes**. An empty state is a *claim about the user's data*; making it before the data has spoken is
how a slow read becomes "Tenshu lost my timeline".

So the state now carries `daysLoaded` / `placesLoaded` / `activitiesLoaded`, and `hasNothing` is
`daysLoaded && days.isEmpty() && boundTrips.isEmpty()`. Pinned by a test with a repository that
**never emits**.

⚠ Bound chapters count as history even when no day does: a user whose days have aged out of the
home window still has a timeline, and offering them an import as the whole screen answers a question
they did not ask.

### 9.2 The empty state is the import (TB-D6)

Timeline's whole premise for someone who has not enabled capture — or cannot, on desktop — is
*bring your history*, so the import lives on the screen rather than only behind a Settings row
nobody opens on a first launch.

⚠ It must not read as "capture is broken". Capture is off by default (threat-model A35) and staying
off is a legitimate way to use the app, so it is mentioned second and as an alternative.

⚠ The button is absent where nothing binds a `TimelineImportControl` — the same bound-only-shows
idiom the Settings rows use, so a platform with no picker (iOS today) shows the explanation and no
dead control. The render test starts Koin **empty** precisely to hold that case up.

⚠⚠ The import outcome is shown *here* too, not only in Settings: an import that found nothing
leaves this screen empty, and without the reason on it the user is looking at the same blank page
they started from — which is exactly how "it did nothing" gets reported when what happened was
"that archive held no timeline".

### 9.3 ⚠ A tab with nothing is a different, quieter sentence

"Places appear as your timeline fills in" — not "your timeline starts here". A user with ten years
of imported history and no named places has plenty of data; conflating the two would tell them they
have none and offer them an import they already did.

### 9.4 What is proven

- **5 state tests**: not-yet-answered, answered-empty, any history, bound-chapters-only, and
  becoming empty again after a data reset (the state follows the data rather than latching).
- **5 render tests** (`runDesktopComposeUiTest`): the invitation and its capture line, ⚠⚠ the
  *absence* of the invitation while the read is in flight, the feed when there is history, and both
  Places-tab cases. Rendered rather than asserted on state alone because the defect being closed was
  visual — every state assertion in the tree passed while the screen was blank.
- Full gate green; **Android smoke gate PASSED**.

⚠ Not proven: nobody has looked at this on a phone. It is a first-launch screen, so the on-device
pass B2 already owes covers it.

---

## 10. As built — B5: the photos, and the coordinate their sidecar knows (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:file-service` (**v5**), `-client`, `-proxy`,
`:takeout-import`, `timeline-shared-data`, `timeline-shared-ui`, both shells.

⭐⭐ **This is where Tracks A and B meet.** A photo exported from Google Photos carries no usable
EXIF, and now arrives with the coordinate from its JSON sidecar — so
`photosNear` finds it, and "show me my pictures from Munich" answers over a corpus that is years
deep instead of over the handful of photos backed up from a Tenshu device. Pinned end to end by
`andIsThenFoundByPlace`.

### 10.1 The op parameter that was designed and never wired

`UploadFileImplementation` has taken a `captureLocation` since T-P4 with **no caller and no wire
representation**: the Android path its KDoc describes was designed and never built (what actually
made Android work was `setRequireOriginal` handing the proxy unredacted bytes). B5 is its first
user, so `UploadFile` gained two optional params.

⚠ **Both or neither.** A latitude without a longitude is not half a coordinate, it is a wrong one.

⚠ HR #9, exactly as written: `SchemaShapeBaselineEnforcementTest` caught the reshape (an
operation's params serialize positionally just as a composite's fields do), so **`FileService` is
v5** with an empty migration step registered — empty because an operation carries no stored rows,
and registered because that is what keeps a v4 store inside its derived floor and migrating rather
than wiping. ⚠⚠ The bump is owed for the *wire*: a proxy that predates these params drops a photo's
coordinate **silently**, and the import would look like it worked while the photos stayed
unfindable by place.

### 10.2 ⚠⚠ The sidecar is the authority, and (0,0) is not a place

`readPhotoSidecar` is hand-parsed for the same reasons `ExifGps` is: a handful of fields out of a
dozen, shapes that differ between export vintages (`photoTakenTime` / `creationTime`, `geoData` /
`geoDataExif`), and a module that is dependency-free by design.

- ⚠ `geoData` outranks `geoDataExif` — a user who corrected a photo's place in Google Photos meant
  the correction.
- ⚠⚠ **`(0,0)` is no coordinate**, the same rule `ExifGps` already applies. Takeout writes exactly
  that for every photo it has no location for; reading it literally would gather a decade of
  someone's photographs into one spot in the Atlantic — and, worse, inside the radius of any place
  they ever visited near null island. Test-pinned, including that the photo is *not* then findable
  at (0,0).
- ⚠ The timestamp is **seconds, as a string**, beside a `formatted` field that is a localised date
  nothing can parse. Reading the pretty one is the obvious mistake.

### 10.3 ⚠⚠ Two passes, and why the seam had to start suspending

A sidecar can appear **after** the image it describes and a streaming reader cannot go back — so
pass one reads the sidecars (small, JSON) and pass two uploads the images with what pass one
learned. Buffering the images instead means holding a photo export in memory, which is the one
thing this design exists to avoid. Pinned by a fixture whose sidecar deliberately comes second.

⭐ `TakeoutArchive.walk`'s consumer is now **suspending**, and that is load-bearing: B5 uploads each
photo from inside the walk and drops it before the next entry is read. The first draft queued the
uploads because the callback was not suspending — which for a photo export is the whole archive in
memory, exactly the failure the streaming reader was chosen to prevent.

### 10.4 ⚠⚠ Opt-in, and off by default

"I imported my location history" must not quietly upload forty gigabytes of photographs into the
user's realm — and their storage quota. The photo half is a separate switch, off by default, stated
plainly, and `importTakeout` runs the timeline **first** so a user who closes the app midway still
has their history.

⚠ The summary reports photos separately **and says how many carried a location**. That is the
number "pictures from Munich" actually depends on, and an import that brought in 4 000 photos of
which 0 are placeable is a result the user should see rather than discover later as an empty search.

⚠ Photos land flat under `/Takeout Photos`. Takeout's album folders are Google's organisation, not
the user's; recreating them would import a filing system nobody asked for.

### 10.5 What is proven

- **8 sidecar tests**: both time fields, both geo fields and their precedence, both hemispheres,
  ⚠⚠ null island, nothing-useful, and garbage-never-throws.
- **6 import tests** over a real proxy and a real DEFLATE archive: the coordinate reaches the row,
  ⭐⭐ the photo is then **found by `photosNear`**, the sidecar-comes-second case, a photo with no
  location, null island, and the folder.
- Baseline regenerated; full gate green; **Android smoke gate PASSED** (the un-built canaries still
  admitted sessions, which is what a service-version bump must not disturb).

### 10.6 ⚠ What B5 does not do

- **Nobody has run this over a real photo export.** Everything about the size — hours of uploading,
  the store's growth, what a phone does when the app is backgrounded halfway — is unmeasured, and
  the on-device pass B2 owes now covers this too.
- ⚠⚠ `importFiles` still holds **every timeline file's bytes at once**. It did not block B5 (the
  photo half streams), but it is the same archive, and it is the next thing to measure.
- **No resume.** TB-D8's answer stands — content-addressed ids make a re-run free — but for a photo
  export "run it again" means re-reading tens of gigabytes to skip what is already there.
- Videos are classified as photos and uploaded; nothing gives them a poster frame yet.
