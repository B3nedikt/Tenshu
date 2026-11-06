# Track B — Control → Data UI + `core-realm-data` (+ Rei parts)

**Goal:** Ship the **Data** section of Control as a *generic* data-inventory surface driven by
the `RealmData` trait — no per-type `if/else`. Land the `RealmData` trait machinery in `:core`
/`:core-common`, declare it on `File`, surface it through the JS SDK, and render one card per
declared data type (count + latest item, newest-first), with Files as the first type.

**Owning surfaces**
- Kotlin core: `:core/.../trait/RealmData.kt`, `:core-common` DTO + registration +
  `SchemaProvider.allRealmData()`; `:file-service` File declaration.
- Control UI: `control-shared/.../ui/screens/data/**`.
- Web: `web/apex/src/control/data/**`, route `/control/data` → `/control/data/files`.
- JS SDK: `:tenshu-sdk-js` schema-walker + `@tenshu/sdk/core` `realmData()`.
- Docs (same PR): `core-realm-data.md` status, `tenshu-rei.md` §2.4 (small note).

**Primary references**
- `docs/proposals/core-realm-data.md` — the whole proposal; especially §2 (decisions),
  §3.1 (trait + factory + registration), §3.2 (`allRealmData()`), §3.3 (Control Data
  consumer), §4 (phased plan), §8 (open question: CREATED_AT binding), §9 (file-by-file).
- `docs/proposals/control-ia.md` §5 — Data inventory (Files = meta-view, **not** a browser;
  Devices live under Data via §2.3 but are **out of scope** for this track — already covered
  by Settings, per §6 of the proposal).
- `docs/proposals/web-control-plane.md` §2 (routes), §4.3 (Data rendering), §8 (phasing).
- Platform: `docs/agent.md` §3 (Trait / `TraitSchema` / `SchemaProvider` / properties &
  relations), `docs/schema-dsl.md` (trait DSL). Template trait: `:core/.../trait/Syncable.kt`.
- Paging extension to reuse: `core-common/.../util/PagingUtil.kt` (`TList<T>.pagingSource`),
  `drive-shared/.../data/FilesPaging.kt` (`toPagingData` expect/actual bridge).

---

## Current state (from exploration)

- `core-realm-data.md` is **spec'd, not started**. None of `RealmData.kt`, `RealmDataDto`,
  `allRealmData()`, or the `File` declaration exist.
- `RealmData` is a **`Trait`** (Syncable-shaped: interface + value-bearing `TraitSchema` +
  `RealmData(...)` factory + generic registration in `CoreCommonModule`) — **not** the old
  composite-registered annotation.
- Paging extension exists and is the listing mechanism (`pagingSource` / `toPagingData`).
- Control `Data` destination is a placeholder; no Data screen exists.
- **Main open question (§8):** the CREATED_AT requirement→property *binding* for the
  newest-first sort. `File` has both `CreatedAt` and `LastModifiedAt`, so it must be explicit.

---

## Phase B1 — `RealmData` trait + factory + `allRealmData()`  (= proposal Phase 1)

Per `core-realm-data.md` §3.1, §3.2, §4 Phase 1, and the §9 file list.

1. `:core/.../trait/RealmData.kt` — interface + `RealmData.Schema` (`TraitSchema`) carrying
   value fields: `displayName` (TString), `pluralDisplayName` (TString), `iconName`
   (TString), `sortOrder` (TInteger). Plus the requirement constant
   `RealmData.CREATED_AT` (a `ValueTraitRequirement` for a `UnixInstant` property). Declare
   trait requirements: `Syncable.Schema`, `DataIdProvider.Schema`, and `CREATED_AT`.
2. `:core/.../trait/RealmData.kt` factory:
   ```kotlin
   fun RealmData(displayName: String, pluralDisplayName: String,
                 iconName: String, sortOrder: Int): TraitSchema
   ```
3. `:core-common/.../impl/schema/RealmDataDto.kt` — DTO mirroring the four fields.
4. `:core-common/.../module/CoreCommonModule.kt` — register the **single generic** trait
   factory (mirror the `Syncable` registration): `trait(dataSchema = Data.Schema, traitSchema
   = RealmData.Schema, traitFactory = { _, schema -> … })` + the DTO factory.
5. `:core/.../core/SchemaProvider.kt` — add
   `fun allRealmData(): List<RealmData>` (doc: "every type that *declares* the trait, ordered
   by `sortOrder` ascending").
6. `:core-common/.../proxy/SchemaRegistry.kt` — implement: walk `allSchemas()`, filter to
   **declared** traits (not inherited — so `Folder` does not appear twice), materialise via
   `getTrait(typeId, RealmData.Schema)`, sort by `sortOrder` (stable). No reverse index (Phase
   6 optional optimisation). Update test fakes + the JS in-process `SchemaRegistry`.

**B1 tests (gate):** `RealmDataTest` + `RealmDataIntegrationTest` in `:core`/`:core-common` —
two fixture schemas enumerate in `sortOrder` order; a subtype that inherits the trait does
**not** create a duplicate entry. Exit: `:core` + `:core-common` compile, `./gradlew check`
green.

---

## Phase B2 — Declare `RealmData` on `File` + resolve CREATED_AT  (= proposal Phase 2)

Per §4 Phase 2 + §8.

1. `:file-service/.../File.kt` — add to `File.Schema` traits:
   ```kotlin
   RealmData(displayName = "File", pluralDisplayName = "Files",
             iconName = "description", sortOrder = 100)
   ```
2. **CREATED_AT binding (the §8 decision — make it here):** a type can carry several
   `UnixInstant` properties, so the fulfilment must be explicit. **Recommended v1 surface:**
   add a binding in `:file-service`'s module config declaring `File.CreatedAt` fulfils
   `RealmData.CREATED_AT` (a `ValueTraitRequirement` binding entry), and a
   `SchemaProvider.resolveRequirement(typeId, RealmData.CREATED_AT): Property.Value<UnixInstant>?`
   helper the Data consumer uses for the newest-first sort. If the binding surface proves
   contentious in review, **hardcode `File.CreatedAt` for the single v1 type** and generalise
   alongside Phase 5 (requirement enforcement) — but write the helper signature now so the
   consumer code doesn't change later.
3. **Folder:** inherits the trait for resolution but folds into File — **not** a separate
   card. **Device:** deferred (Settings already covers it). Do not declare either.

**B2 tests (gate):** `getTrait(File.ID, RealmData.Schema)` returns the expected trait;
`allRealmData()` returns exactly **one** File entry (no Folder duplicate); File collection
lists newest-first via paging sorted by `File.CreatedAt`. Run `:file-service*` tests +
`:core-common` tests.

---

## Phase B3 — JS SDK walker awareness  (= proposal Phase 3)

Per §4 Phase 3 + `web/architecture.md` §12.9 (schema-walker pattern).

- Extend `:tenshu-sdk-js/.../internal/CompositeSnapshot.kt` (schema walker) to surface traits.
- JS in-process `SchemaRegistry` reproduces `allRealmData()`.
- `@tenshu/sdk/core` exposes `Tenshu.realmData(): Promise<RealmDataType[]>` where each entry
  carries `{ typeId, displayName, pluralDisplayName, iconName, sortOrder }`.
- Exit: File entry renders in web walker; no Folder duplicate.

---

## Phase B4 — Control Data section (consumer)  (= proposal Phase 4)

This is the **UI**. Build it generically — driven by `allRealmData()` / `Tenshu.realmData()`,
never a hard-coded type list (IA §5 invariant). Per `control-ia.md` §5 + `web-control-plane.md`
§4.3.

### Native (control-shared)
1. **Destinations:** `Data` top-level already exists — give it the real screen. Add
   `@Serializable data class DataType(val typeId: String) : Destination()` for the per-type
   listing.
2. **`DataScreen` / `DataScreenContent`** (`screens/data/`): one card per `RealmData` entry,
   ordered by `sortOrder`. Each card: icon (`iconName`), `pluralDisplayName`, **count** +
   **latest item** — both derived from the paging path (first page + page metadata), not a
   bespoke inventory op (§2.5: there is deliberately **no** `InventorySummary`). Files card is
   a **meta-view**, not a browser; "Browse" routes into Drive.
3. **`DataTypeScreen`** (`screens/data/`): newest-first list of items for one type via the
   paging extension (`toPagingData`). Clicking a file routes into Drive.
4. **VMs:** `DataViewModel(schemaProviderClient)` exposes the card list;
   `DataTypeViewModel(typeId, …)` exposes the paged items. Add a thin control-shared client
   (e.g. `RealmDataClient`) wrapping `SchemaProvider.allRealmData()` + per-type paging, so VMs
   stay UI-only (mirror `EventRepository`'s paging shape).
5. Wire `viewModel { }` + `entry<Destination.Data>` / `entry<Destination.DataType>` + tab.

### Web (web/apex)
- Route `/control/data` → 302 `/control/data/files`; render `<DataPage />` as a generic walker
  over `Tenshu.realmData()` (no hard-coded list). `/control/data/files` = Files inventory
  meta-view. **Devices routes are out of scope here** (Track-independent; already in
  `web-control-plane` plan — leave the existing/placeholder).
- Smoke test: register a synthetic second `RealmData` type and confirm a second card appears
  with zero code change in the consumer.

**B4 tests (gate):** `:control-shared:desktopTest` — `DataScreenContentTest` (cards render
from a fake `allRealmData()`, ordered by sortOrder, count+latest shown, empty state),
`DataTypeScreenContentTest` (paged items newest-first). `:control-desktop:compileKotlinJvm`.
Web: component test for the generic walker (synthetic-type smoke). Android: mark unverified.

---

## Out of scope (keep the diff focused)
- Phase 5 (requirement enforcement) and Phase 6 (cheap total count) — both optional, defer.
- Devices under Data (covered by Settings). Photos/Places/Notes/Contacts (future types appear
  automatically once they declare `RealmData`).
- Third-party data types (gated on Rei operational).
- Drill-in "preferred app" concept (v2).

---

## `tenshu-rei.md` parts this track lands

Small, but in-PR (House Rule #2):

- **§2.4 (Public/private split by verification boundary)** — add a note that the Data section
  enumerates **realm-private** data types via `allRealmData()`; the metamodel (`RealmData`
  trait) is the schema-side ontology that a future ledger-published third-party data type
  would also declare. I.e. record that `core-realm-data` is the mechanism by which both
  first-party (private) and eventual third-party (public, ledger-verified) data types surface
  uniformly in Control — the verification boundary changes the *source*, not the Data UI.

Also update `core-realm-data.md` status (Phases 1–4 landed; CREATED_AT binding decision
recorded; Phases 5–6 still optional) and refresh the `AGENTS.md` Realm-Data row.

---

## Definition of done
- `RealmData` trait + generic factory + `allRealmData()` landed; `:core`/`:core-common` tests
  green.
- `File` declares `RealmData`; `allRealmData()` returns exactly one File entry; newest-first
  sort resolves via `File.CreatedAt`.
- JS SDK `Tenshu.realmData()` surfaces the File type (no Folder dup).
- Control Data section renders generically (native + web): one card per type, count + latest,
  per-type newest-first list; synthetic-second-type smoke passes with zero consumer changes.
- `core-realm-data.md` + `tenshu-rei.md` §2.4 updated in-PR; Android wiring unverified.
