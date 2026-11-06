# Reference Property — Resolve a Foreign Entity by a Stored Key

**Status:** Phases 1–3 **implemented + verified** on
`feat/file-modification-device-relation` (the primitive + the `FileModification.Device`
consumer + the Drive activity UI); only the Phase 4 threat-model note remains. Driving
consumer: `FileModification.deviceId` →
`Device` ("modified on <device>") on the Drive FileDetailsScreen activity list,
the device-names item deferred in
[`drive-file-details-screen.md`](drive-file-details-screen.md) §6 and
[`file-service-history.md`](file-service-history.md) §9.4. Author: Benedikt + Claude.

**Scope:** a small platform primitive (`:core` + `:core-common`) — a `referenceProperty`
builder + a *dereference-by-key* resolution branch that lets a **value property** resolve
*one live foreign entity by a key field on the receiver*; plus its first consumer
(`:file-service` declares `FileModification.Device`; `:drive-shared` renders the name).
**No wire-format change** to stored data, **no relay/sync-protocol change** — resolution
is a local `Database.getData` at the proxy. See §5 for the threat-model check.

---

## 1. Motivation

`FileModification` rows carry a raw `deviceId: TString` (`FileModification.kt:50`) for
"modified on <device>" attribution. The FileDetailsScreen activity list renders that raw
id today; the proposal deferred resolving it to a human name because it "needs a `Device`
lookup in identity-service that `:drive-shared` doesn't depend on" and was gated on "the
over-fetching deserializer tech-debt." That tech-debt landed (projection pushdown),
so resolving a relation via `select` no longer drags the whole graph along — but pushdown was
*necessary, not sufficient*. The real gap: **the platform can't resolve a foreign entity
by a stored key.**

The general shape — "this field holds the key of an entity of type X; resolve it" — is a
foreign key. It recurs (the next cross-service relation will want it too), so the
primitive is worth building once, narrowly.

## 2. The key realisation: this is a keyed fetch, not a join

`Device` is keyed by its `deviceId` field via a `StringDataIdProviderSchema`
(`Device.kt:61`). A stored `Device` therefore lives at the `DataId`
`StringDataId("tenshu/identity/device", deviceId)`. So resolving
`FileModification.deviceId → Device` is **not** a `getDevices().search { deviceId == … }`
scan — it is a direct, `O(1)` keyed fetch:

```
key      = thisModification.getField(FileModification.Field.DEVICE_ID)   // the deviceId string
dataId   = StringDataId(Device.ID, key)
device   = executionContext.database.getData(dataId, asEntity = true)    // the stored Device, or null
```

This collapses the design in three important ways:

1. **No cross-service *operation* invocation.** The thing that genuinely isn't wired is a
   resolver reaching another service's operations (`IdentityService.getDevices()`). We
   never need it — `Database.getData` is already reachable from resolution, and the
   resolution is a plain entity fetch by id.
2. **No dynamic chain.** Resolution is one fetch keyed off an existing field — not a
   computed pipeline. So this is a *value property* (declarative) whose value happens to
   be dereferenced, not a `dynamicProperty` (a `ChainTemplate` + materializer). See §3.
3. **`:file-service-proxy` does *not* depend on `:identity-service-proxy`.** Resolution
   runs at the proxy that hosts file-service — which for Drive is **Control**, and Control
   also hosts identity-service, so `Device` entities (Eager-synced) are already in the
   same local store and `Device.Schema` is already registered there.

## 3. Design — a reference value property

### 3.1 What exists vs. what's missing

Composite-typed value properties already exist: `File.Category: Property.Value<FileCategory>`
(`File.kt:109`, registered `FileServiceCommonModule.kt:282`). What's missing is
**dereference**: `resolveStaticProperty` returns `dataEntity.properties[ref]` *verbatim*
(`ReferenceResolver.kt:261`) — it never fetches another entity. `File.Category` works
because its value is a stored constant in the entity's own cell, not a pointer to a
separate, live entity. So a *plain* value property for `Device` could only store a
**snapshot** at write time (stale on device rename, duplicated per row). We want the
**live** `Device`, single source of truth — so the one new behaviour is dereference-by-key.

### 3.2 The primitive: a composite value property + a proxy-side deref

**No new wire type.** Static properties of every flavour already emit the *same* wire
selector — `StaticPropertyRelation` carrying a `PropertyTypeReference` (`PropertyRelation.kt:23`)
— and register as the *generic* `PropertyRelationSchema` (`RelationSchema.kt:20`; there is a
`DynamicPropertyRelationSchema` but **no** static counterpart). So the static wire
description is already uniform across constant (`File.Category`), scalar (`File.Size`), and
composite. A `ReferencePropertyRelationSchema` would wire a *resolution* concern into the
*wire* format for nothing — **rejected.** `FileModification.Device` is a plain
`Property.Value<Device>` (composite-typed value property, like `File.Category` but an
entity), emitting the same `StaticPropertyRelation`. `Property` stays the sealed
`Value | Dynamic`.

The one new behaviour is a **proxy-side deref branch** in `resolveStaticProperty`. Today it
returns `dataEntity.properties[ref]` verbatim (`ReferenceResolver.kt:261`); for a
composite-valued static property it instead resolves the reference to the live entity. This
is not new machinery — the projection-pushdown work already landed
`CompositeDatabaseReference` + `Database.getComposite(ref)` (a "composite stored by
reference, resolved on use" seam); a composite value property is exactly that. Null-tolerant:
a missing `Device` resolves to `null`, the UI falls back to the raw `deviceId`, no relay
round-trip, no pending marker, non-OnDemand. Composes with projection pushdown — `getData`
already takes a `Projection`, so `relations(Device) { fields(name) }` can later read only
the name (v1 materialises the whole `Device`).

**Decided (2026-05-25): (B) derive from the existing `deviceId` field** — no stored cell,
no duplication, no write-path change.

The deref is **entirely the existing `DataId` mechanism** — no new fetch primitive:
`DataId.StringDataId.create(ctx, Device.ID, key)` + `Database.getData(dataId, asEntity = true)`,
both already on the resolution path. The **target type** comes free from the property's own
value schema (`Property.Value<Device>` carries `Device.Schema` → `Device.ID`). And the
**handle needs no new builder** — `valueProperty<Device>(id, Device.Schema)` already yields
a `Property.Value<Device>` (exactly like `File.Category`, just an entity target).

So the *only* irreducible new bit is the **"key = which receiver field"** link — because
nothing today connects `FileModification.deviceId` to `Device`'s identity. That's a tiny
**proxy-side registration** hint (precedent: the value factory at `registerValueProperty`,
held in a `ModuleConfiguration` function/data map, never serialised), so the wire stays the
uniform `StaticPropertyRelation`. Net new code for the primitive: a `referenceProperty(fromId,
property, keyField)` registration (= `registerPropertyRelation` so the selector projects +
a `(propertyId → keyField)` entry) and a ~4-line deref branch in `resolveProperty`. (A)
"store the reference" was the considered alternative — rejected because `deviceId` already
exists as a field, so a stored reference would duplicate it and cost a write-path change
across the 4 emit hooks.

### 3.3 The consumer: `FileModification.Device`

Declared in **`:file-service`** (alongside `File.Size` / `File.Category` — the main module
*is* the API surface), not `:file-service-common`:

```kotlin
// :file-service — FileModification.kt
private val fileModificationDevice: Property.Value<Device> =
    referenceProperty<Device>(
        id = "${FileModification.ID}/device",
        target = Device.Schema,
        keyField = FileModification.Field.DEVICE_ID,
    )
val FileModification.Companion.Device: Property.Value<Device> get() = fileModificationDevice
```

Registered in `fileServiceCommonModule` next to the `File.*` properties:
`referenceProperty(fromId = FileModification.ID, property = FileModification.Device, keyField = …, targetTypeId = Device.ID)`.

### 3.4 Module wiring (the minimum edges)

| Module | Change | Why |
|---|---|---|
| `:core` | the composite-value-property builder (a `valueProperty` over a composite target; `Property` unchanged) | the authoring surface — **no new wire type** (§3.2) |
| `:core-common` | the proxy-side deref branch in `resolveStaticProperty` (reuse `getComposite`); the (A or B, §7.1) registration | resolve the live entity at the proxy — nothing serialised |
| `:file-service` | `+ implementation(project(":identity-service"))` **(not `api`)**; `FileModification.Device` handle | the `Device` type; `implementation` so non-users don't pull identity (consumers opt in with their own dep) |
| `:file-service-common` | `+ implementation(:identity-service-common)`; the property registration line | registration references `Device.ID`/`Device.Schema`; `-common` stays implementation-only |
| `:drive-shared` | register `:identity-service-common` (wire) + `:identity-service-client` (typed `Device` RO) in `tenshuClientModule`; map in `FileMapper`/VM | so the resolved `Device` parses + reads typed on the Drive client ([[wire-registration-gap]], [[file-service-filter-registration]]) |

`:file-service-proxy` is untouched — resolution needs only the DB + `Device.ID`, both
present at Control. **Compile risk to verify (Phase 2):** Kotlin/KMP referencing an
`implementation`-dep type (`Device`) in a *public* declaration in `:file-service`. Expected
to compile (consumers need their own dep to *use* the `Device` return type — the desired
opt-in); if KMP forces `api`, that would re-leak identity to all consumers, so confirm
early. A separate `:file-service-identity` module is the fallback, **rejected** here per
"the main module carries the whole API."

### 3.5 The UI

`FileDetailsViewModel` already streams the activity rows; the chain's `select` gains the
relation and the mapper reads the resolved name (fallback to id):

```kotlin
.select { fields(…); relations(FileModification.Device, …existing…) }
val deviceName = mod.get(FileModification.Device)?.name?.get() ?: mod.deviceId.get()
row(kind, detail, timestamp, deviceName)   // "Renamed · on iPhone · 2h ago"
```

## 4. Why not the alternatives

- **`dynamicProperty` + a `ResolveReference` chain op** (the first draft of this proposal).
  Heavier — a `ChainTemplate`, the materializer path, and a new operation — for a value
  that is one keyed fetch off an existing field. A reference *value* property is the
  declarative fit. **Rejected** (maintainer: "dynamic brings no benefit here").
- **A plain value property storing a `Device` snapshot.** Zero platform change, but
  denormalises: stale on device rename, a `Device` copy per modification row. Live
  dereference (§3.2) keeps a single source of truth and needs no write-path change (it
  derives from the existing `deviceId` field). Rejected; snapshot kept in mind only if a
  *point-in-time* device name is ever explicitly wanted.
- **`getDevices().search { deviceId == … }`.** `O(devices)` scan per row *and* needs
  cross-service operation invocation (not wired) → couples `:file-service-proxy` →
  `:identity-service-proxy`. The keyed fetch is `O(1)` and needs neither. Rejected.
- **Client-side join in `:drive-shared`.** Ships names with zero platform change but
  doesn't generalise and pushes a cross-service concern into every consumer. The fallback
  if the primitive slips; not chosen.

## 5. Threat-model check

Resolution is a **local `Database.getData` at the proxy** — no new relay data, no wire
shape change, no sync-protocol change. Assets A1–A4 and A9 are untouched, so House Rule
#2's hard amendment trigger is not hit. One **capability** change to record: an app that
declares/reads `FileModification.Device` (Drive, via the new client deps) gains read
visibility into the realm's `Device` *names*. Within a single-user realm this is the
user's own data surfaced to the user's own app — benign. For future **shared/multi-user
realms** the device list of *other* users would become inferable from file attribution,
so revisit before sharing ships (tracked alongside `file-service-history.md` §4.5 /
T-R-09's attribution residual). One-paragraph note added to `docs/sync/threat-model.md` in
the implementing PR.

## 6. Phases

1. **Primitive (`:core` + `:core-common`). ✅ LANDED.** `SchemaProvider.getReferenceProperty`
   (default `null`) + `ReferencePropertyDescriptor` in `:core`; `ModuleConfiguration`
   `referenceProperties` map + `registerReferenceProperty` + the `referenceProperty(fromId,
   property, keyField)` DSL + the proxy-side deref branch in `ReferenceResolver.resolveProperty`
   (reads the receiver's key scalar → `StringDataId(target, key)` → `Database.getData`) in
   `:core-common`. **No wire type** (§3.2): the selector stays `StaticPropertyRelation`, no
   parser/Dto work. Verified by `ReferencePropertyTest` (resolves the live target by key;
   null when target absent; null when key field absent) — `:core-common:desktopTest` green.
2. **`FileModification.Device` (`:file-service` + `:file-service-common`). ✅ LANDED.**
   `FileModification.Device = valueProperty<Device>(…, Device.Schema)` handle in
   `:file-service`; `referenceProperty(fromId = FileModification.ID, property = …Device,
   keyField = DEVICE_ID)` registration in `fileServiceCommonModule`. `:file-service` +
   `:file-service-common` take `implementation(:identity-service…)` (**not `api`**) — the
   §3.4 compile risk is resolved: it compiles, and identity stays off consumers'
   classpaths. Verified by `FileModificationDeviceTest` (over real `Device` +
   `FileModification` + a real `Database` via `TestProxy`): resolves the authoring device's
   name by `deviceId`; null when the device is unknown locally. `:file-service-common:jvmTest`
   green. **Note:** the key field must be a *mandatory* scalar (resolution reads it with
   `getField`; the real `CompositeDto` rejects `getOptionalField` on a mandatory field).
3. **Drive UI (`:drive-shared`). ✅ LANDED.** `:identity-service{,-common,-client}` added
   (`implementation`); `identityServiceCommonModule` + `identityServiceClientModule`
   registered in `tenshuClientModule` (Device wire + typed RO on the Drive client).
   `FileActivity` gains `deviceName` + a `deviceLabel` (resolved name, else raw id);
   `TenshuFilesRepository.fileActivity` adds `.select { … relations(FileModification.Device) }`;
   `FileMapper.mapActivity` reads `get(FileModification.Device)?.name`; the activity row
   renders `"on <deviceLabel>"`. **Implementation note:** `select` projects *only* the named
   selectors, so the chain must also `fields(...)` every scalar `mapActivity` reads **plus the
   `compositeKey`** (the row's dataId key — otherwise `GetStringDataId` fails on the
   fieldless projected entity). The resolved value uses `getData(asEntity = false)` so the
   `Property.Value<Device>` decode (`as? Device`) resolves. Verified on desktop:
   `FileDetailsScreenTest` (renders "on iPhone" + the "on d1" fallback) +
   `FileDetailsIntegrationTest` (chain intact, graceful fallback when no device is local);
   full `:drive-shared:desktopTest` green. **Android rendering itself unverified** (no
   Android SDK in the build env) — flag for an Android Studio check.
4. **Threat-model note + projection refinement (optional).** The §5 paragraph; the
   `relations(Device) { fields(name) }` nested-projection narrowing once measured worth it.

## 7. Open questions

1. **Storage: (A) store the reference vs (B) derive from the `deviceId` field** (§3.2).
   Both keep the wire uniform (`StaticPropertyRelation`) and the deref proxy-side — this is
   purely where the resolver reads the key. (A) = truest value property + maximally uniform
   resolver, costs a write-path change + a duplicated key; (B) = no duplication/no write
   change, costs a small proxy-side key hint. Lean (B) since `deviceId` already exists as a
   field. Settle before Phase 1.
2. **Null shape.** A missing target must collapse to `null` through `Property.Value.decode`
   *without* minting a `PendingOnDemandProperty` — verify `SelectImplementation`'s pending
   path only fires for OnDemand relations; gate if not. Phase 1/2.
3. **Key generality.** v1 assumes the receiver's key-field value *equals* the target's
   `StringDataId` key (true for `deviceId↔deviceId`). A target keyed by a different/derived
   field would consult the target's `DataIdProvider`; out of scope until a second consumer
   needs it.
4. **`implementation` vs `api` for `:file-service` → identity (§3.4).** The one compile
   risk; resolve at the start of Phase 2.

## 8. Non-goals

- A general join / reverse-join engine (`file-service-history.md` §9.4) — this resolves
  *one* entity by *one* key, not arbitrary correlated queries.
- Cross-service *operation* invocation from a resolver — explicitly avoided (§2).
- A new `Property` kind — `referenceProperty` returns the existing `Property.Value` (§3.2).
- Writing/storing the reference (derived from the existing `deviceId`; no new stored cell).
