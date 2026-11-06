# Realm Data — Surfacing Tenshu's Data Types to Consumers

**Status:** Phases 1–2 landed. Phase 1: `RealmData` **marker** trait + the
`RealmData.CREATED_AT` value requirement + `SchemaProvider.realmDataTypeIds(): List<String>`.
Phase 2: `File.Schema` declares the marker (`RealmData.Schema` in its `traits` list);
`File.CreatedAt` bound to `RealmData.CREATED_AT` via the new
`ModuleConfiguration.fulfilValueRequirement` / `SchemaProvider.resolveValueRequirement` surface
(the §8 question, now resolved, see below). Phase 3: `TenshuSdk.realmDataTypeIds(): Promise<string[]>`
on the JS SDK (`@tenshu/sdk/core`) — no schema-walker change needed since the marker has no fields
to flatten; it just enumerates `SchemaRegistry.realmDataTypeIds()`. Phase 4 landed **(lean
scope)**: the Control Data section renders as a **generic walker** — one card per
`realmDataTypeIds()` entry — on both native (`control-shared` `DataScreen`/`DataViewModel` +
`RealmDataClient` over the in-process proxy `SchemaRegistry`) and web (`web/apex`
`/control/data` `DataPage` over `TenshuSdk.realmDataTypeIds()`). Each platform owns a
**presentation registry** keyed by type id (`realmDataPresentation` — label/icon/order, English
literals, the single localisation seam per ADR 0016) with a derived fallback so unknown/future
types still render a card (verified by a synthetic-second-type smoke test). **Deferred from
Phase 4** (a documented follow-up, not built): per-type **count + latest** and an in-Control
per-type **item list** — both need a uniform per-type collection that `File` lacks today (its
realm-wide list is a folder-tree traverse, not a `ServiceDataRelation`), so adding them means
either a flat all-files relation on `:file-service` or a per-type collection-resolver. Type
cards are therefore a meta-view with no in-Control drill-in yet (browsing routes into the
owning app — cross-app nav glue, also follow-up).

**Implementation note — `RealmData` is a fieldless marker, not a value-bearing trait
(supersedes the value-bearing design wherever it appears below — §2.1/§2.5/§2.7, the §3.1–§3.3
code, §4 Phase 1–2/§7 exit criteria, and the §8 `sortOrder` bullet: `allRealmData(): List<RealmData>`
→ `realmDataTypeIds(): List<String>`; no display/icon/`sortOrder` fields; no generic factory).**
The original design had the trait carry display metadata
(`displayName`, `pluralDisplayName`, `iconName`, `sortOrder`) as inline fields, materialised via
a generic factory like `Syncable`. That was wrong on two counts:

1. **Localisation / layering.** Display name + icon are *localisable, consumer-specific
   presentation*; baking literal English into the wire-shared, consumer-agnostic metamodel is
   the wrong layer. Each consumer (native `control-shared`, web apex) owns its own presentation
   — a registry keyed by type id, localised in that consumer — with a fallback for
   unknown/third-party types. `sortOrder` is presentation too (ordering between types), so it
   moves out as well; the trait does not order its own enumeration.
2. **Requirement typing.** The pseudo-code `require(Syncable.Schema)` / `require(DataIdProvider.Schema)`
   is type-unsound (a `TraitSchema` is not a `TraitRequirementSchema`; iterating the
   `TList<TraitRequirementSchema>` throws `ClassCastException`; no "requires-trait-X" primitive
   exists). Those stay **prose** in the KDoc until the optional Phase 5 enforcement pass.

So `RealmData` is now a **fieldless marker** (shaped exactly like `HeadlessSafe`): `trait(ID,
traitRequirements = listOf(valueTraitRequirement(CREATED_AT)))`, **no** value fields, **no**
factory, **not** registered in any module. A type opts in by placing `RealmData.Schema` directly
in its `traits` list; enumeration walks the schema traits (`realmDataTypeIds()`). The only thing
the trait asserts beyond membership is the creation-timestamp **data contract** (`CREATED_AT`),
which is structural (the sort *key*), not presentation. **`File`'s presentation for the
control-shared registry (Phase B4):** `displayName = "File"`, `pluralDisplayName = "Files"`,
icon `description`, order `100` — recorded here so the values aren't lost; they live in the
consumer, not the schema.

**Scope:** `:core` (`trait/RealmData.kt` — the marker), `:core-common`
(`module/ModuleConfiguration*` + `proxy/SchemaRegistry.kt` — `realmDataTypeIds()` +
`fulfilValueRequirement` / `resolveValueRequirement`; `core/SchemaProvider.kt` —
the interface surface), `:file-service` (`File.kt` — the first `RealmData` declaration
on `File.Schema`) + `:file-service-common` (the `File.CreatedAt` binding), `:tenshu-sdk-js`
(schema walker — surface the marker + enumerate it), `:control-shared` (consumer: the Data
section + the **presentation registry** that owns display name/icon/order per type id, per
`web-control-plane.md` §4.3 and the future native Control screens spec). No `RealmDataDto` —
the marker is never serialised (§9), like `Syncable`.

This doc fixes a gap surfaced by [`control-ia.md`](control-ia.md) §5: the IA commits
to a Data section that surfaces every data type in the realm uniformly — Files
today; Devices, Places, Photos, Notes, future types as they ship — but there's no
metamodel marker that tells a consumer *which* composite schemas count as "realm
data" or what display metadata each one carries. Hard-coding the list works for v1
(one known type) and stops working the moment a second type ships.

The proposal introduces a **`RealmData` trait**: a schema-side marker that declares a
composite as a data type Tenshu provides to apps and surfaces to consumers. The
trait carries display metadata (name, icon, sort order) and a reference to the
collection a consumer lists the type's items from. Control consumes it to render the
Data section; apps consume it to discover what they can ask Tenshu about; future
tooling (JS SDK walker, schema-introspection inspectors) consumes it to generate
types and docs.

**This is not a registration *with* Control.** Realm data types are part of Tenshu's
metamodel — its ontology of what exists. Tenshu *provides* these types and their
interfaces; Control is one consumer among several. The trait describes Tenshu's
contract, not Control's runtime configuration.

---

## 1. Motivation

### 1.1 The IA claim has no implementation

`control-ia.md` §5.1 lists Files (and Devices) for v1 and states that *"the set is
open-ended; new data types added by feature services or apps register with Control to
appear here."* That sentence describes a mechanism that doesn't exist. Files appear
in a hypothetical Data section because the apex's React component would hard-code a
list including them. The same hard-coded list would appear in native Control. Both
codebases drift independently as new types ship, and neither codebase gets to ask
*"what data types does this realm contain?"* through a uniform query.

### 1.2 Apps need the same answer

Apps installed on the platform consume Tenshu data types through the SDK. Drive
needs to operate on Files; Timeline needs Places; Mia might need access to a
combination. Today an app discovers types by importing the relevant feature
service's umbrella module — `import org.tenshu.core.file.File` — and using it
directly. That's fine when the app is first-party and bundled at compile time. It
breaks for:

- The JS SDK exposing typed surfaces to web apps (`@tenshu/sdk/files` knows about
  File because `tenshu-sdk-js` was hand-written to know about it; adding Place
  requires a hand-written addition).
- Future third-party apps loading via Rei publications, which won't know which
  Tenshu data types exist without runtime introspection.
- Tooling like a future `.d.ts` emitter or a Rei publication-validator that wants
  to enumerate the platform's surface.

A schema-level trait centralises the answer. `SchemaProvider.allRealmData()`
returns the list, every consumer queries the same source.

### 1.3 The relation system rework cleared the path

[ADR 0010](../adr/0010-relation-system.md) Phases 0.5 / 1 / 2 / 3 / 4 / 5.1
landed in May 2026. Concretely useful here:

- `ServiceDataRelation` (Phase 2) replaced `EntityRelation` /
  `EntityListRelation` and keys on a `Type`. The list-vs-single distinction now
  lives in `Type` (e.g. `TList.typeOf(File.Schema)`), and any
  `ServiceDataRelation` whose `type.baseSchema` is `TList` resolves to a
  collection. That gives `RealmData` a uniform way to point at *"the collection my
  items live in"* without re-litigating the list-vs-single shape.
- Dynamic-property chains are now stored as data on the schema (Phase 4), so
  schema introspection can walk a `RealmData` trait and follow its references
  end-to-end without hitting the lambda layer break that used to live in
  `CallStack<*>`. The JS SDK walker (`web/architecture.md` §12.9) can read the
  full trait surface.

### 1.4 Why a trait, not an annotation

The first draft of this proposal modelled `RealmData` as a standalone `Composite`
registered in module config via a `realmData(...)` DSL call, keyed by schema id —
deliberately *not* a trait, on the (mistaken) reasoning that "schema-level markers in
Tenshu aren't traits."

That reasoning was wrong, and the trait shape is the better fit:

- **Traits already are the platform's schema-side marker mechanism.** `Syncable`,
  `DataIdProvider`, and `CompositeDataIdProvider` are all traits attached to a
  schema's `traits = listOf(...)` list and resolved at runtime through
  `SchemaProvider.getTrait(type, traitSchema)`. `RealmData` is exactly that kind of
  thing: "this schema participates in a platform capability." Introducing a parallel
  composite-registered-in-module-config mechanism would have been a second way to do
  what traits already do.
- **The "not a trait" objection conflated two things.** Tenshu has no *naming*
  convention against a `…Trait` suffix — `Syncable` is a trait and carries no
  suffix. `RealmData` likewise stays `RealmData`. The real distinction was
  *attachment-on-schema* (trait) versus *composite-registered-in-module-config*
  (the old annotation), and attachment-on-schema is the right model.
- **Traits carry field values inline.** `Syncable(SyncStrategy.Eager)` is a factory
  that returns a value-bearing `TraitSchema` placed directly in the schema's `traits`
  list, and a single generic factory in `CoreCommonModule` materialises it on lookup.
  `RealmData(displayName = …, iconName = …, …)` follows the identical pattern — all
  the display metadata lives in the trait's fields.
- **Traits can be declared from outside the type they describe.** `registerTrait(
  dataSchema, traitSchema, factory)` is keyed by `(typeId, traitId)` and is decoupled
  from where the `composite(...)` schema is declared (`Syncable` is registered
  generically against `Data.Schema`, not at any one type's site). This is the
  Rust-like "extension trait" model, and it's the existing behaviour — so a future
  third party can attach `RealmData` to a type it doesn't own without new machinery.

Working vocabulary, unchanged:

- **Data type** — informal English, used in `control-ia.md` and this doc. Not a
  Kotlin identifier (the platform's `Type` infrastructure owns the word "type").
- **Realm data** — the Kotlin identifier and the schema-level surface. Captures
  the membership ("this composite is part of what the realm holds") without
  colliding with `Type` / `GenericType` / `TypeVariable`.
- **Ontology / metamodel** — appropriate framing in prose; not a Kotlin identifier.

---

## 2. Design Decisions

These are locked in for the rest of the document.

1. **`RealmData` is a `Trait` that lives in `:core/.../trait/`.** It is shaped like
   `Syncable`: a `Trait` interface with a value-bearing `TraitSchema` and a
   `RealmData(...)` factory function. It is attached to a data-type schema via that
   schema's `traits = listOf(...)` list and resolved through `SchemaProvider.getTrait`.

2. **A single generic trait factory is registered in `CoreCommonModule`,** exactly
   like `Syncable` and `DataIdProvider`. Each data type simply adds `RealmData(...)`
   to its own `composite(...)` declaration; no per-type factory registration.

3. **Enumeration reuses the existing schema registry, no reverse index.**
   `SchemaProvider.allRealmData()` walks the registered schemas and keeps the ones
   that **declare** `RealmData`, materialising each via `getTrait(typeId,
   RealmData.Schema)`. The concrete `SchemaRegistry` already holds the iterable
   (`allSchemas()` / the `traitFactories` map keyed by `(typeId, traitId)`). The call
   is rare (only when a consumer opens the Data section), so the linear walk + sort is
   acceptable; a memoised `Map<traitId, List<typeId>>` is an available optimisation if
   it ever becomes hot, but is out of scope for v1.

4. **Enumeration keys off *declared*, not *inherited*, traits.** Traits inherit down
   the schema hierarchy (`Folder.Schema` parents `File.Schema`, so `getTrait(
   Folder.ID, RealmData.Schema)` resolves via inheritance). That is correct for
   *resolution* — a folder *is* realm data of type File — but a sub-type must not
   appear as a separate card. Because the factory is registered against the
   *declaring* schema (`File`), `Folder` has no entry of its own and the enumeration
   naturally surfaces File once. This is the desired "annotation per concrete leaf,
   sub-types fold into their parent" outcome, achieved for free.

5. **The trait points at a collection, not at an inventory operation.** `RealmData`
   resolves the type's collection from the type id via its `ServiceDataRelation`
   (per §1.3 — one primary collection per type, §6); items are sorted newest-first by
   the creation-timestamp property the trait requires (§2.6). The Data section lists
   items through the existing paging extension
   (`FilesPaging` / `PagingUtil` / `TenshuPagingSource`). There is **no**
   `InventorySummary` composite and no per-service inventory operation — count and
   "latest item" fall out of paging metadata and the first page. (See §6 if a true
   cheap total count later becomes load-bearing.)

6. **`RealmData` declares three trait requirements: `Syncable`, `DataIdProvider`,
   and a creation-timestamp value property.** A realm data type must be syncable
   (it's user data that lives in the realm), addressable (its items need stable ids to
   be listed and navigated to), and must expose a creation timestamp (a
   `ValueTraitRequirement` of type `UnixInstant`, id `RealmData.CREATED_AT`) so the
   Data section can sort items newest-first generically. `File` fulfils the third with
   `File.CreatedAt`. **Caveat:** the platform declares trait requirements today but
   does not *enforce* or *resolve* them (every existing trait declares an empty list,
   and nothing validates or binds them). Declaring them documents the contract now;
   the enforcement pass is Phase 5 (optional) and requirement *resolution* for the sort
   binding is an Open Question (§8).

7. **Icon is a Material icon name in v1.** Both `ui-core` (M3) and the web apex
   (M3 tokens) consume the Material icon set; using its name (`description`,
   `devices`, `place`, `image`) is the most cross-platform-portable v1 choice. A
   custom-icon extension point is noted in §6 but not committed in v1.

8. **No drill-in route on the trait in v1.** *"Where does clicking a Place item
   navigate?"* is a consumer concern. Control's web rendering knows File items
   navigate to `/drive/files/...`; native Control knows they navigate to a Compose
   `FileDetailScreen`. v2 adds a preferred-app concept if it becomes load-bearing —
   see §6.

9. **Wire format**: `RealmData` is a trait resolved from module config at runtime,
   like `Syncable`. Re-registered on every startup; never serialised to disk or
   sync. If a future feature ships realm-data declarations to remote consumers
   (e.g. third-party app manifests carrying their own data types), it gets a
   separate proposal covering the serialisation.

10. **First-party scope.** v1 covers data types declared by core feature services.
    `File` is the sole v1 declaration. Third-party apps shipping their own data
    types is deferred — it depends on Rei publications being operational and on a
    manifest parsing path that doesn't exist today. §6.

---

## 3. Final Shape

### 3.1 The `RealmData` trait

Shaped after `Syncable` (`:core/.../trait/Syncable.kt`): a `Trait` interface, a
value-bearing `TraitSchema`, and a `RealmData(...)` factory.

```kotlin
// :core/.../trait/RealmData.kt

interface RealmData : Trait {

    val displayName: TString
        get() = getField(Fields.DISPLAY_NAME)
    val pluralDisplayName: TString
        get() = getField(Fields.PLURAL_DISPLAY_NAME)
    val iconName: TString
        get() = getField(Fields.ICON_NAME)
    val sortOrder: TInteger
        get() = getField(Fields.SORT_ORDER)

    companion object {
        const val ID = "tenshu/core/trait/realmData"

        val Schema: TraitSchema = trait(
            id = ID,
            traitRequirements = {
                // declared (enforcement is Phase 5, optional — see §2.6)
                require(Syncable.Schema)
                require(DataIdProvider.Schema)
                // A creation-timestamp value property, type UnixInstant. The Data
                // section sorts items newest-first by whatever property fulfils this.
                // File fulfils it with `File.CreatedAt`.
                requireValue<UnixInstant>(CREATED_AT)
            },
            fields = {
                string(Fields.DISPLAY_NAME)
                string(Fields.PLURAL_DISPLAY_NAME)
                string(Fields.ICON_NAME)
                integer(Fields.SORT_ORDER)
            },
        )

        /** Requirement id for the creation-timestamp value property (§2.5). */
        const val CREATED_AT = "$ID/createdAt"
    }

    object Fields {
        const val DISPLAY_NAME = "$ID/displayName"
        const val PLURAL_DISPLAY_NAME = "$ID/pluralDisplayName"
        const val ICON_NAME = "$ID/iconName"
        const val SORT_ORDER = "$ID/sortOrder"
    }
}

/**
 * DSL factory for a [RealmData] trait requirement, mirroring `Syncable(...)`.
 * Returns a value-bearing [TraitSchema] for placement in a schema's `traits` list.
 */
fun RealmData(
    displayName: String,
    pluralDisplayName: String,
    iconName: String,
    sortOrder: Int,
): TraitSchema = /* object : TraitSchema, Composite by … carrying the field values,
                    exactly as Syncable(...) does */
```

The sort key is no longer a free-form `sortPropertyId` string on the trait. Instead,
`RealmData` *requires* a creation-timestamp value property (a `ValueTraitRequirement`
of type `UnixInstant`, requirement id `RealmData.CREATED_AT`). A type satisfies the
requirement by binding one of its value properties to it — `File` binds `File.CreatedAt`.
This makes "has a creation date" part of the **contract** of being realm data rather
than something each consumer has to be told per type, and the Data section sorts every
type generically by the property bound to `RealmData.CREATED_AT`.

The one wrinkle (§8): generic sorting needs requirement *resolution* — mapping
`RealmData.CREATED_AT` to the concrete fulfilling property on each type — which is more
than the Phase 5 *enforcement* pass (presence-checking). A type can have several
`UnixInstant` value properties (`File` has both `CreatedAt` and `LastModifiedAt`), so the
binding must be explicit, not "any property of the right type." See §8.

The collection the type's items are listed from is the type's existing
`ServiceDataRelation` (per §1.3) — resolved by the consumer from the type id, so it
is not duplicated as a field on the trait. (If a type has more than one plausible
"primary" collection, a `collectionRef` field is the obvious addition; File has one,
so v1 doesn't need it.)

A generic factory in `CoreCommonModule` materialises the trait on lookup, exactly
as `Syncable` and `DataIdProvider` do:

```kotlin
// :core-common/.../module/CoreCommonModule.kt
trait(
    dataSchema = Data.Schema,
    traitSchema = RealmData.Schema,
    traitFactory = { _, schema ->
        val composite = schema as Composite
        object : RealmData, Composite by composite {
            override val schema get() = schema as TraitSchema
            override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            // field reads delegate to `composite`
        }
    },
)
```

### 3.2 `SchemaProvider` access

```kotlin
// :core/.../core/SchemaProvider.kt
interface SchemaProvider : TComponent {
    // ... existing surface (getTrait, getSchema, getServiceDataRelation, …)

    /** Every data type that declares the [RealmData] trait, ordered by sortOrder ascending. */
    fun allRealmData(): List<RealmData>
}
```

`allSchemas()` currently lives on the concrete `SchemaRegistry`, not the
`SchemaProvider` interface. `allRealmData()` is the interface-level surface; the
`SchemaRegistry` implementation walks its registered schemas, filters to those that
**declare** `RealmData` (not inherit — §2.4), materialises each via
`getTrait(typeId, RealmData.Schema)`, and sorts by `sortOrder`. Test fakes and the
JS in-process `SchemaProvider` implement the same method.

### 3.3 Consumer shape — Control's Data section

```kotlin
// Pseudo-code for the apex's Data page (web/apex/...)
// or :control-shared's DataScreen (native)

class DataPageViewModel(private val schemaProvider: SchemaProvider) {

    fun loadDataTypes(): List<DataTypeRow> = schemaProvider.allRealmData().map { rd ->
        DataTypeRow(
            displayName = rd.displayName.get(),
            pluralDisplayName = rd.pluralDisplayName.get(),
            iconName = rd.iconName.get(),
        )
    }

    // Items for a type come from its existing collection, sorted newest-first via the
    // standard (stable) paging extension — no per-type inventory operation. The sort
    // key is the value property the type bound to RealmData.CREATED_AT (§2.6).
    fun pagedItems(typeId: String): Flow<PagingData<Entity<*>>> =
        schemaProvider.collectionFor(typeId)
            .sortBy(schemaProvider.createdAtPropertyFor(typeId), descending = true)
            .pagingData()
}
```

The Data page is a **generic walker** — no per-type if-else, no hard-coded list of
File / Device / Place. New data types appear automatically as their feature services
add `RealmData(...)` to their schema declaration. Count and "latest item" come from
paging metadata and the first page; there is no separate summary contract.

---

## 4. Implementation Plan

### Phase 1 — `RealmData` trait + generic factory

**Goal:** the new `Trait`, its `RealmData(...)` factory, the generic factory
registration, and `SchemaProvider.allRealmData()` exist and compile across the
modules they touch. No type declares the trait yet.

Files added / changed:

- `:core/.../trait/RealmData.kt` — interface + `Schema` + `RealmData(...)` factory.
- `:core-common/.../impl/schema/RealmDataDto.kt` — DTO mirroring the structure.
- `:core-common/.../module/CoreCommonModule.kt` — generic `trait(dataSchema =
  Data.Schema, traitSchema = RealmData.Schema, …)` factory + DTO factory registration.
- `:core/.../core/SchemaProvider.kt` — `allRealmData()` interface method.
- `:core-common/.../proxy/SchemaRegistry.kt` — `allRealmData()` implementation
  (walk schemas → declared-`RealmData` filter → `getTrait` → sort by `sortOrder`).

Tests:

- `RealmDataTest.kt` — `RealmData(...)` → DTO → parse → equals round-trip; field reads.
- `RealmDataIntegrationTest.kt` — declare `RealmData` on two fixture schemas, verify
  `allRealmData()` returns them in `sortOrder` order; verify a *sub-type* of one
  fixture schema does **not** produce a duplicate entry (the inheritance/enumeration
  contract from §2.4).

**Exit criteria:** `:core` and `:core-common` compile; `gradle check` green for both.
No production declarations yet.

### Phase 2 — Declare `RealmData` on `File`

**Goal:** the v1-canonical data type declares the trait.
`SchemaProvider.allRealmData()` returns File in production builds.

`:file-service/.../File.kt` — add to `File.Schema`'s `traits` list:

```kotlin
val Schema: CompositeSchema = composite(
    id = ID,
    parent = Composite.Schema,
    traits = listOf(
        FileDataIdProvider.Schema,
        Syncable(SyncStrategy.Eager),
        RealmData(
            displayName = "File",
            pluralDisplayName = "Files",
            iconName = "description",
            sortOrder = 100,
        ),
    ),
    fields = { string(id = Field.PATH, type = FilePath.Schema) },
)
```

`File.CreatedAt` (the existing immutable creation-timestamp value property) is bound as
the fulfilment of `RealmData.CREATED_AT`, so the Data section sorts files newest-first
without any per-type sort-field configuration. (The exact binding surface — how a type
declares "`File.CreatedAt` fulfils requirement `RealmData.CREATED_AT`" — is the
resolution question in §8; for File it is the only `UnixInstant` property that
represents creation, `LastModifiedAt` being mutable.)

`Folder` (whose schema parents `File.Schema`) inherits the trait for *resolution*
but is **not** separately enumerated (§2.4) — it folds into File for the Data
section, which matches the file system's mental model.

**Exit criteria:**

- `schemaProvider.getRealmData`-equivalent (`getTrait(File.ID, RealmData.Schema)`)
  returns the expected trait; `allRealmData()` from a `:file-service-proxy`
  integration test contains exactly one File entry (no Folder duplicate).
- The existing File collection lists items newest-first via the paging extension,
  sorted by the property bound to `RealmData.CREATED_AT` (`File.CreatedAt`).

### Phase 3 — JS SDK walker awareness

**Goal:** the JS SDK's schema walker (`tenshu-sdk-js/.../internal/CompositeSnapshot.kt`)
surfaces the `RealmData` trait so the JS surface can enumerate data types without
per-type bridging code.

Concretely:

- Extend the walker so a schema's traits are visible to JS, and a `RealmData` trait
  is flattened to the JS-side shape. The trait can be built lazily in the walker on
  first request rather than eagerly for every schema.
- The JS in-process `SchemaRegistry` reproduces `allRealmData()` (same
  declared-trait enumeration as the JVM registry).
- `@tenshu/sdk/core` exposes `Tenshu.realmData()` as an async method returning the
  array; the hand-written TS adapter in `web/sdk` re-exports it.

**Exit criteria:**

- `@tenshu/sdk/core` exposes the realm-data list to JS consumers; web apex
  smoke-test verifies the single known (File) entry and the absence of a Folder
  duplicate.

### Phase 4 — Control Data section consumes the registry

**Goal:** the Data section in `web-control-plane.md` §4.3 becomes a generic
`<DataPage />` walker over `Tenshu.realmData()`, with items listed via the standard
paging path. The v1 type is *expressed through* its `RealmData` trait rather than
living in the component.

This is the payoff: the migration of File proves the mechanism, and the Data
section is correct by construction for any future type.

**Exit criteria:**

- Files appear in `/control/data` as the generic walker renders them (visual
  parity with the hard-coded design).
- A smoke test adds a synthetic second `RealmData` declaration in a test fixture
  and verifies a second card renders with no component changes — this synthetic
  type carries the proof of genericity, since File is the only real v1 type.

**As-built (2026-10-24) — native cards show only types that hold data.** Control registers
every first-party service's schema whether or not its app is installed, so the bare
`realmDataTypeIds()` walk showed *Routine* and *Run* cards to a user who only has Drive.
`RealmDataClient.hasData(typeId)` now filters the list: a cheap `count()` over each
service-data array holding the type (`serviceDataArraysHolding`, the same route
`DeleteRealmData` uses), plus Drive's root folder for `File`, whose rows live in the folder
tree. `count()` on an unfiltered array reads the member list only, never the rows.

- ⚠ `null` (can't tell) **shows** the card. Hiding a type the probe could not read would hide
  data the user has. A type with no collection to count therefore always shows, and
  `RealmDataPresenceIntegrationTest` pins that every type declared today answers `false` on an
  empty realm. A new type that answers `null` fails it.
- ⚠ A one-shot check, not a live flow. `DataViewModel.refresh()` runs on init, on every
  screen resume, and after every reset, so a card appears on the next visit after another app
  stores data.
- ⚠ Files = "Drive's root folder has any child", so a leftover empty `/Trash` or `/Backup`
  keeps the card after everything is deleted, and files only in a shared realm don't count.
  Both err toward showing.
- ⚠ The web `DataPage` still lists every declared type.

### Optional phases (useful, not required for v1)

Phases 1–4 ship a complete, working Data section. The following are worth doing but
are **not** blockers — File already satisfies everything Phases 1–4 need, and each of
these can land independently, later, without reworking the above.

#### Phase 5 (optional) — Trait-requirement enforcement

**Goal:** make declared trait requirements actually mean something platform-wide.

`RealmData.Schema` declares `Syncable` + `DataIdProvider` as requirements (§2.6), but
nothing validates trait requirements today — every existing trait declares an empty
list and no pass checks them. This phase adds a validation pass (at config
registration / startup) that, for each schema, walks its declared traits, reads each
trait's `traitRequirements`, and fails loudly if a required trait is absent. Value
extends beyond `RealmData` (it's the first real consumer, not the only beneficiary).

**Why optional:** File already carries both `Syncable` and a `DataIdProvider`, so the
requirements hold in practice for v1. Shipping declared-but-unenforced requirements is
a small footgun, not a correctness bug, so this can follow.

**Exit criteria:** registering a fixture schema that declares `RealmData` without
`Syncable` fails at registration with an actionable error; existing schemas (incl.
`File`) still register cleanly.

#### Phase 6 (optional) — Cheap total count

**Goal:** a true total count per type without paging through the whole collection.

v1 surfaces "newest N" via paging, and count/latest fall out of paging metadata
(§2.5). If a consumer later wants an exact total cheaply, add a per-type count surface
then. This explicitly replaces the abandoned `InventorySummary` approach with the
minimal thing actually needed.

**Why optional:** the Data section is fully functional on paging metadata alone; a
total count is a presentational nicety until a consumer demands it.

**Exit criteria:** decided and specced only if/when a consumer needs it — no
speculative implementation.

---

## 5. Relationship to Other Work

### 5.1 vs. the relation system rework

Both are about how schemas describe themselves. The relation-system rework
([ADR 0010](../adr/0010-relation-system.md)) was about *links between data types*
(single-vs-list service relations, dynamic-property chains as data). This proposal
is about *which data types exist* and what to call them. They compose: `RealmData`
*reuses* `ServiceDataRelation` as the collection its items list from (§2.5), but it
doesn't change any relation-schema declaration; relations don't change how types are
enumerated.

### 5.2 vs. the trait system

`RealmData` is an ordinary `Trait`. It reuses the existing attachment
(`traits = listOf(...)`), resolution (`SchemaProvider.getTrait`), generic-factory
(`CoreCommonModule`), inheritance, and serialisation machinery — the same paths
`Syncable` and `DataIdProvider` use. The one capability it leans on that no current
trait exercises is **trait requirements**: `RealmData.Schema` declares `Syncable`
and `DataIdProvider` as requirements. The schema layer supports declaring them
(`TraitSchema.traitRequirements`, the typed `ValueTraitRequirement`), but no
enforcement pass exists today (all current traits declare empty requirement lists).
Building that pass — walk each schema's traits, read each trait's requirements, fail
loudly at registration if unmet — is valuable beyond `RealmData` and is **Phase 5
(optional)**. It is not a blocker for File, which already satisfies both requirements.

### 5.3 vs. operation-schema typing

[ADR 0009](../adr/0009-operation-schema-typing.md) landed full `Type`
infrastructure on operation declarations. `RealmData` doesn't extend that — it
points at a collection (a `ServiceDataRelation`), and a consumer that wants typed
operation handles looks them up through the existing resolvers.

### 5.4 vs. the JS SDK schema walker

The walker (`web/architecture.md` §12.9) flattens Composites into JS-native
objects. `RealmData` is a trait with primitive fields; Phase 3 adds (a) surfacing a
schema's traits to JS and (b) the `allRealmData()` enumeration on the SDK side. The
trait can be materialised lazily by the walker on first request.

### 5.5 vs. the future native control-screens spec

The native steady-state Control screens (the follow-up doc named in
`web-control-plane.md` §6) consume `RealmData` the same way the apex does. The
walker pattern is platform-uniform: `SchemaProvider.allRealmData()` works
identically on JVM and JS.

---

## 6. Out of Scope

- **Third-party apps shipping their own data types.** Because traits can be attached
  from outside the declaring type (§1.4), a third party could in principle declare
  `RealmData` on a type via its manifest. The trust and validation story for that is
  non-trivial: manifest parsing, schema instantiation at install time, Rei signature
  verification, conflict resolution if two apps claim the same type. Wait for Rei to
  be operational and the use case to materialise.

- **Trait-requirement *enforcement* (in this proposal, but optional).** `RealmData`
  *declares* `Syncable` + `DataIdProvider` requirements (§2.6, §5.2), but the
  platform-wide pass that validates trait requirements at registration doesn't exist
  yet. Declaring is in v1 scope; building the enforcement pass is **Phase 5
  (optional)** — useful, not a blocker.

- **Multiple collections per type.** A type has exactly one primary collection,
  resolved from its id via its `ServiceDataRelation`. We don't expect ever to need more
  than one per type, so there is no `collectionRef` field and no multi-collection
  surface — left out by design, not deferred.

- **A cheap total count (in this proposal, but optional).** v1 surfaces "newest N" via
  paging; count and latest fall out of paging metadata. A true total count without
  paging is **Phase 6 (optional)** — it is not part of the trait's v1 contract, and
  explicitly replaces the abandoned `InventorySummary` approach.

- **Devices in v1.** Devices are realm data, but they already have a dedicated
  Settings surface, so a Data-section card for them is redundant for now. `Device`
  can declare `RealmData` later by the same one-line addition File uses; the
  mechanism is type-agnostic.

- **Custom icons.** v1 commits to Material icon names. A custom-icon mechanism
  (SVG path, base64 data URI, Tenshu-hosted icon registry) is left as an extension
  point — when a data type can't fit a Material icon, this proposal is amended to add
  a fallback `customIcon: TByteArray?` field or similar.

- **Drill-in route on the trait.** `RealmData` doesn't carry "where to navigate when
  the user clicks an item." Consumers handle that locally for v1. v2 may add a
  preferred-app concept if cross-consumer drill-in becomes a real shared concern.

- **User-defined data types.** Tenshu is a platform for typed data declared in
  schemas, not an open database. Out forever.

- **Per-type permissions on listing.** "Can app X list Files?" is a semantic
  permission question handled by the existing permission-mediation layer (per
  `web/architecture.md` §6 and `control-ia.md` §4). `RealmData` declares the type's
  existence and presentation; whether a given caller can invoke its collection is
  enforced elsewhere.

---

## 7. Test Plan

**Phase 1:**

- `RealmDataTest.kt` — `RealmData(...)` → `RealmDataDto` → parse → equality; field reads.
- `RealmDataIntegrationTest.kt` — declare `RealmData` on two fixture schemas, verify
  `allRealmData()` returns them in ascending `sortOrder`; declare a sub-type of one
  fixture schema and verify it does **not** appear as a separate entry (§2.4).
- `SchemaProviderTest.kt` — `getTrait(typeId, RealmData.Schema)` returns the correct
  value or null.

**Phase 2 (File declaration):**

- `FileRealmDataTest.kt` — `getTrait(File.ID, RealmData.Schema)` returns the expected
  trait; `getTrait(Folder.ID, RealmData.Schema)` resolves via inheritance but
  `allRealmData()` contains File exactly once.
- Cross-module integration test in `:control-shared`: walk `allRealmData()`, page the
  File collection newest-first by the property bound to `RealmData.CREATED_AT`
  (`File.CreatedAt`), verify the first page returns the most recently created files.

**Phase 3 (JS SDK):**

- `Tenshu.realmData()` from a Vitest test in `web/sdk` returns a one-element array
  (File); the entry has the expected fields; no Folder duplicate.
- The flattened JS shape matches the TS interface declared in `web/sdk` (wherever it
  lands).

**Phase 4 (Control Data section):**

- Apex Vitest: render `<DataPage />`, assert one card (Files) plus correctly paged
  items.
- Add a synthetic second `RealmData` declaration in a test-only schema; assert a
  second card renders with no component changes.

---

## 8. Open Questions

- **Creation-date requirement resolution / binding. — RESOLVED (Phase 2).** A type binds its
  fulfilling property explicitly via `ModuleConfiguration.fulfilValueRequirement(fromId,
  requirementId, property)`, stored proxy-side as `(typeId, requirementId) → Property.Value<*>`
  (a Kotlin handle, never serialised — like `referenceProperties` / `entityRelations`).
  `SchemaProvider.resolveValueRequirement(typeId, requirementId): Property.Value<*>?` resolves
  it, walking the type's parent chain so a sub-type inherits its parent's binding (`Folder`
  resolves via `File`). `fileServiceCommonModule` binds `File.CreatedAt` to
  `RealmData.CREATED_AT`; the Data section (Phase 4) sorts a type's collection newest-first by
  the resolved handle (`sortBy(handle)` accepts any `Property<*>`). Chosen over hardcoding
  `File.CreatedAt` in the consumer because the consumer must stay a **generic walker** (IA §5);
  the binding surface is generic (keyed by `requirementId`) so a second requirement or a
  third-party type reuses it. Surface shipped with Phase 2, so File sorts for real. Independent
  of the optional Phase 5 *enforcement* (presence-checking) pass.

- **Ordering between types — now the consumer's.** Superseded: `sortOrder` is no longer a
  trait field (it's presentation; see the marker note at the top). `realmDataTypeIds()` returns
  ids in config-registration order; the consumer's presentation registry decides display order.
  Collisions / sparse integers are a non-issue at the schema layer.

- **Renaming a data type.** Display name is presentational and lives in the consumer (which
  localises it), not the schema. The broader question of how schema/field migrations work
  across releases is a real gap, but it's a cross-cutting platform concern, not
  `RealmData`-specific, and is out of scope here.

- **Cheap total count.** Deferred to Phase 6 (optional). Revisit if/when a consumer
  needs a true count without paging.

---

## 9. References

- [`docs/proposals/control-ia.md`](control-ia.md) §5 — the IA claim this proposal backs.
- [`docs/proposals/web-control-plane.md`](web-control-plane.md) §4.3 — the web
  consumer of the registry.
- [ADR 0010](../adr/0010-relation-system.md) — landed relation-system rework that
  cleared the path for schema-side data introspection; `ServiceDataRelation` is the
  collection `RealmData` points at.
- [ADR 0009](../adr/0009-operation-schema-typing.md) — typed-operation infrastructure
  (orthogonal but adjacent).
- `:core/.../trait/Syncable.kt` — the trait this proposal is shaped after (interface +
  value-bearing `TraitSchema` + factory + generic registration in `CoreCommonModule`).
- [`docs/web/architecture.md`](../web/architecture.md) §12.9 — the JS SDK schema walker
  that surfaces `RealmData` in Phase 3.
- [`docs/agent.md`](../agent.md) — Service & Operation pattern, schema DSL, trait
  registration. The conventions this proposal follows.
