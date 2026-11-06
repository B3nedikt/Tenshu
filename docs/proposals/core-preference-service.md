# Preference Service — Synced Per-App / Per-Device Settings

**Status:** Built and in use — `RealmSetting` / `DeviceSetting` rows over `genericData`, with
`REALM` / `DEVICE` / `OVERRIDABLE` scopes and reactive reads on the client surface. ⚠⚠ Parts of
this document describe a design that was abandoned during implementation — read the banner
directly below before §3.1, §4.1–§4.3 or §5.

⚠⚠ **Two designs below did not ship, and the shipped code is the opposite. Read this before
§3.1, §4.1–§4.3 or §5.**

1. **`Setting` is a non-parameterised marker, NOT `Setting<V>`.** §3.1's decision table and
   §4.1–§4.3's code declare `typeParams = { type("V") }` with `field(id = Field.VALUE, type =
   typeVar("V"))`. That shape was abandoned during implementation: the DB `dbEntryMapper` cannot
   resolve `typeVar`-typed value fields, and a parent contributing fields collides with
   `DataFactory.collectFields`' field-inheritance walk (a double-counting hazard). `Setting`
   carries no traits and declares no fields; `preferenceId` + `value` live on the concrete
   subtypes. See `preference-service/…/Setting.kt`.
2. **The stored `value` is a self-describing `Data`** via the `genericData()` field kind +
   `DbValue.GenericValue`, superseding the opaque-`TByteArray` interim. ⚠ `genericData` stores an
   inline primitive **or** a composite reference — **not arrays** — which is why list-valued
   preferences still ride as `TByteArray` blobs (see the §5 table note).

The sections are left as-written for the design record. **Do not implement from them.**

**Phase state** (the `**Status:**` line above is the doc's status; this is the phase detail):
Phases 1–4 landed. Phase 5 (scheduler integration) — the
preference→`ConstraintSpec` resolver + `ConstraintSpec.maxDataMb` landed; its
*enforcement* (a real sync `schedule(...)` call + Android constraint binding) is
gated on `core-background-processing.md` Phase 6. Phase 6 (`OVERRIDABLE`
resolution) is held until the first concrete consumer. Independent of the
in-flight tracks (`core-background-processing.md` is the first scheduled
consumer — see §10.1 — but neither blocks the other on the spec side).

**Scope:** new module set `:preference-service` + `-common` / `-client` / `-proxy`
/ `-test-utils` (symmetric with the existing services), two new `Syncable`
composites, a typed declaration object (`Preference<T>`), and a thin
`PreferenceService` SDK surface. Touches `:control-shared` and `:drive-shared`
only at the call sites where today's local-only state moves to the new service.

This proposal defines how user-facing settings (sync over Wi-Fi only, monthly
data cap, files kept offline on this device, theme, etc.) are stored, scoped,
and surfaced. Settings ride the existing realm sync channel — they are part of
the user's encrypted realm, not platform-local prefs.

---

## 1. Motivation

### 1.1 Today's situation

The platform has two stores for "key-value-ish state", both Android-specific
and both leaking out of their intended job:

- **`SecureStorageDelegate` (`:core-common`)** — backed on Android by
  `EncryptedSharedPreferences` (deprecated in `androidx.security.crypto`).
  Used for both master-key-grade secrets (`RECOVERY_PHRASE`, `UNLOCK_WRAPPER`,
  `VOLUME_KEY`) **and** plain per-device strings (`DEVICE_ID`,
  `LAST_SYNC_TIMESTAMP`). The mixing is accidental — secrets and non-secrets
  ended up in the same delegate because both happen to be string-shaped.
- **Nothing for user-facing settings.** There is no Wi-Fi-only toggle, no
  "keep this file offline", no theme preference, no per-app scoping mechanism.
  Adding one ad-hoc per app would mean each app reinvents persistence + the
  cross-device propagation story.

### 1.2 The user-facing settings we know we want

Two concrete settings drive this proposal — both come up in
`core-background-processing.md` and `milestones.md`:

1. **Sync over Wi-Fi only** (and a related **monthly data cap**) — controlled in
   Control, applied by Control's scheduler, but Drive also wants to display +
   write the same toggle from its search-bar settings wheel. Same value seen
   from two UIs.
2. **Files kept offline on this device** — controlled in Drive, applied by
   Drive's own offline-keeper, but visible from any device ("file foo.jpg is
   pinned offline on the Pixel"). One setting, many readers.

These differ in a fundamental way:

- **(1)** is **realm-wide** — set once, every device behaves the same. The
  user's intent is "my data only flows on Wi-Fi, period."
- **(2)** is **per-device** — different devices have different storage, so
  pinning is naturally a property of *this* device.

A third pattern is foreseeable but not in v1 scope:

- **Overridable realm default** — admin sets the realm default to "Wi-Fi only,"
  one device opts out for a mobile-hotspot scenario. v1 ships the storage
  shape that makes this easy to add later, but no resolution logic until a
  concrete consumer asks.

### 1.3 Constraints

Three constraints shape the design:

- **House-rule 4 (schema-first for cross-platform data, `AGENTS.md`).**
  Settings are cross-platform domain data; they must be schema-backed and
  sync-eligible.
- **Apps must not have to add new realm composites just to declare a setting.**
  Every preference being its own `Syncable` composite would bloat the schema
  registry, complicate evolution, and require Drive to add a composite per
  toggle. Storage uses **two** generic composites for the entire system; new
  preferences are added by declaring a `Preference<T>` object in code.
- **No schema-side annotations introduced for this work.** The schema DSL today
  doesn't have field-level metadata annotations; adding `@Preference` purely
  for settings is overkill. Metadata (id, default, display name) lives on the
  Kotlin `Preference<T>` declaration object — outside the schema layer.

---

## 2. What this is *not*

To make the scope precise:

- **Not a replacement for `SecureStorageDelegate`** — secrets that must
  never leave the device (`RECOVERY_PHRASE`, `UNLOCK_WRAPPER`, `VOLUME_KEY`)
  remain in a hardware-backed local store. A parallel cleanup will rename
  that delegate to `MasterKeyStore` and rebuild its Android backend on
  AndroidKeyStore + DataStore; that's a separate doc.
- **Not for the narrow set of state that *can't* or *shouldn't* sync** —
  the device's own cryptographic identity (`DEVICE_ID`, generated at
  pairing time and unique per physical install), per-device sync
  bootstrap state (`LAST_SYNC_TIMESTAMP`, the polling cursor), and
  high-frequency ephemera (scroll position, current dialog, last
  opened folder). These land in a future `LocalStore` abstraction.
  **Per-device *preferences* are emphatically *in* scope** — they're
  `DeviceSetting<V>` and DO sync, so a user who replaces a device or
  recovers on a new one sees every other device's preferences from
  the start (see §3.9).
- **Not a general key-value store for apps.** Apps declare typed
  `Preference<T>` objects; the storage layer is hidden. There is no
  `service.putString("anykey", "anyvalue")` escape hatch.

---

## 3. Design decisions

| # | Decision | Why |
|---|---|---|
| 3.1 | Three generic composites for storage: abstract `Setting<V>` super-composite + two concrete `Syncable` subtypes `RealmSetting<V>` and `DeviceSetting<V>`, each parameterised by `V : Data` with a `typeVar("V")` field for the value | Apps don't add composites per preference (per §1.3). The two concrete subtypes cover every scope; the super-composite gives the service one type to talk about "any setting row" and shares the `preferenceId` + `value` field declarations. The typeParam follows the established `Maybe.Some<E>` / `TResult.Success<E>` / `StreamElement.Value<E>` pattern — schema-level type discipline, no new machinery. A `RealmSetting<TBoolean>` is statically known to wrap a `TBoolean`; an SDK chain can pin `Entity<RealmSetting<TBoolean>>.value: TBoolean` without a runtime cast. |
| 3.2 | Preferences declared as typed Kotlin `Preference<T>` objects with a small `PreferenceAdapter<T>` for `T ↔ Data` | Keeps the type information (default, adapter, scope) outside the schema, where it doesn't bloat the realm. The adapter is a tiny round-trip helper — e.g. `BooleanAdapter` wraps via `Boolean.toTBoolean()` / unwraps via `(data as? TBoolean)?.get()`. Composite-valued preferences (future) wrap themselves. |
| 3.3 | Scope is a property of the **Preference declaration**, not the stored row | `RealmSetting` vs `DeviceSetting` is a *storage* concern; the consumer asks `read(pref)` and the service picks the right table. Apps think in terms of "is this preference realm-wide or per-device", not in terms of which composite. |
| 3.4 | Per-device storage keyed by `(preferenceId, deviceId)`, realm storage keyed by `preferenceId` | Standard `StringDataIdProvider` shape, composes with the relation system. |
| 3.5 | Override resolution (`OVERRIDABLE` scope) **is on the read path only**; both rows are stored independently | The realm row is the default; per-device override is a separate row. No "merge" merge logic in the sync layer — orthogonal to `FolderMerger` / `MergerRegistry`. v1 stores the rows; resolution lands when a concrete consumer needs it. |
| 3.6 | No write-time validation against the `Preference<T>` declaration on the proxy side | The proxy doesn't have access to the Kotlin object; it stores whatever `Data` the client writes. Validation is the client's job — same convention as the existing `Operation` request layer. Read-side type mismatches fall back to `pref.default` (see §6.6). |
| 3.7 | Sync conflict strategy: `LastWriterWins` per `(preferenceId, deviceId?)` row | One logical writer per row (`DevicePreference`: only this device; `RealmPreference`: any device, but field-level merge would require splitting each preference into its own row anyway, which we explicitly rejected in 3.1). LWW is honest about that. |
| 3.8 | Control's preference declarations live in `:preference-service`; other apps declare in their own shared module | Control is **always** installed (the proxy/control-plane is part of every Tenshu deployment), so Control's preferences are effectively part of the platform vocabulary. Hoisting them into `:preference-service` lets any consumer reference them without a `:control-shared` dep. Drive's `DrivePreferences.OfflineFiles` and other app-specific preferences stay in `:<app>-shared` — they're only available where that app is. |
| 3.9 | Per-device preferences survive device loss / recovery via the regular sync layer | `DeviceSetting<V>` rows are `Syncable` and keyed by `(deviceId, preferenceId)`. Every device sees every other device's rows from the moment it joins the realm. If a user replaces a device or recovers on a new one, the **old device's preference rows are still there** until the user explicitly removes that device from the realm (at which point the device-management tombstone propagates and clears them). Recovery never silently loses preferences. The new device can either adopt the old device's settings explicitly (write its own rows with the same values) or leave the old rows in place pending removal — both are clean states. |

---

## 4. Storage model

Three composites, declared once in `:preference-service`: an abstract
`Setting<V>` supertype and two concrete subtypes `RealmSetting<V>` and
`DeviceSetting<V>`. **No** other module adds composites to support a new
preference.

Standard schema-DSL shape (`docs/schema-dsl.md`) — same `val Schema = composite(...)`
+ `object Field { ... }` convention every other composite uses. No new
annotation, no new DSL.

### 4.1 `Setting<V>` — super-composite

```kotlin
interface Setting<out V : Data> : Composite {

    val preferenceId: TString
        get() = getField(Field.PREFERENCE_ID)

    val value: V
        get() = getField(Field.VALUE)

    companion object {
        const val ID = "tenshu/preference/setting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            typeParams = { type("V") },
            fields = {
                string(id = Field.PREFERENCE_ID)
                field(id = Field.VALUE, type = typeVar("V"))
            }
        )
    }

    object Field {
        const val PREFERENCE_ID = "$ID/preferenceId"
        const val VALUE = "$ID/value"
    }
}
```

`Setting` is abstract — no `DataIdProvider` trait, no `Syncable` trait — and
declares only the shape common to both scopes (`preferenceId` + typed
`value`). It exists so the service can talk about "any setting row" with a
single type, and so the two scope subtypes don't duplicate the field
declarations.

### 4.2 `RealmSetting<V>` — realm-scoped concrete

```kotlin
interface RealmSetting<out V : Data> : Setting<V> {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/preference/realmSetting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = composite(Setting.ID, typeParams = { type("V") }),
            typeParams = { type("V") },
            traits = listOf(RealmSettingDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {}    // inherits preferenceId + value from Setting
        )

        fun <V : Data> create(
            context: TContext,
            preferenceId: String,
            value: V,
        ): RealmSetting<V> = context.create(
            type = ID,
            fields = listOf(preferenceId, value),
        )
    }
}
```

`RealmSettingDataIdProvider` is a `CompositeDataIdProvider<RealmSetting<*>>`
keyed on `Setting.Field.PREFERENCE_ID`. Type-erased on `V`. One row per
preference per realm.

### 4.3 `DeviceSetting<V>` — device-scoped concrete

```kotlin
interface DeviceSetting<out V : Data> : Setting<V> {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Field.DEVICE_ID)

    companion object {
        const val ID = "tenshu/preference/deviceSetting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = composite(Setting.ID, typeParams = { type("V") }),
            typeParams = { type("V") },
            traits = listOf(DeviceSettingDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(id = Field.DEVICE_ID)   // additive — preferenceId + value inherited
            }
        )

        fun <V : Data> create(
            context: TContext,
            deviceId: String,
            preferenceId: String,
            value: V,
        ): DeviceSetting<V> = context.create(
            type = ID,
            fields = listOf(deviceId, preferenceId, value),
        )
    }

    object Field {
        const val DEVICE_ID = "$ID/deviceId"
    }
}
```

`DeviceSettingDataIdProvider` keys on the composite of `(DEVICE_ID,
Setting.Field.PREFERENCE_ID)`. Type-erased on `V`. One row per `(device,
preference)` pair. All devices see every device's rows — that's the point.

Conflict strategy is `LastWriterWins` per row — the `Syncable` trait's
default. Spelled out per §3.7 because the rows are intentionally
single-writer (`RealmSetting`: one row per preference, written by whichever
device the user toggled on; `DeviceSetting`: one row per `(device,
preference)`, written only by that device).

### 4.4 No explicit `lastModified` field

Every `SyncBlock` already carries an HLC, and conflict resolution at the
sync layer uses that HLC — that's how the `LastWriterWins` strategy
knows which row is the latest. Adding a redundant `lastModified` field on
the composite would just store the same information twice and risk
divergence between "the HLC of the block that carried this row" and "the
value the writer put in the field."

This is also why neither `File` nor `Folder` carry an HLC field —
`File.LAST_MODIFIED` is the *user-meaningful filesystem mtime* of the
underlying byte content (captured at upload), not a sync ordering field.
Preferences have no analogous user-meaningful timestamp: a row's
"when did this value change" *is* its HLC.

If a UI later wants to show "Wi-Fi-only was last toggled on $deviceName at
$time", we read the HLC of the latest `SyncBlock` for that row from the
existing sync metadata — the data is already there. No schema change
needed.

### 4.5 What gets emitted to the realm

A toggle from a client triggers one `SyncBlock` carrying a `RealmSetting` or
`DeviceSetting` entity. No new wire shape, no new merge code, no new envelope.

---

## 5. Preference declarations

Declared in the app/module that owns the preference. The `Preference<T>` value
class lives in `:preference-service`; apps instantiate it directly, grouped under
one `object` per app.

`Preference<T>` is the **pure storage/identity contract** — no presentation. UI
labels are deliberately *not* on it (see §5.4); the type is consumed by the
UI-free service layer and must stay localisation-free.

```kotlin
// In :preference-service
data class Preference<T>(
    val id: String,
    val scope: PreferenceScope,
    val default: T,
    val adapter: PreferenceAdapter<T>,
)

enum class PreferenceScope { REALM, DEVICE, OVERRIDABLE }

/**
 * `T <-> Data` round-trip for one preference value type. Implementations
 * wrap the value as the appropriate Tenshu primitive (TBoolean, TInteger,
 * TString, TByteArray, ...) and cast back on read. Composite-valued
 * preferences pass themselves through unchanged.
 */
interface PreferenceAdapter<T> {
    fun toData(value: T, context: TContext): Data
    fun fromData(data: Data?): T?   // null => use the Preference's default
}
```

Built-in adapters for the obvious primitives:

| Adapter | T | Data wrapper |
|---|---|---|
| `BooleanAdapter` | `Boolean` | `TBoolean` |
| `LongAdapter` | `Long` | `TInteger` |
| `IntAdapter` | `Int` | `TInteger` |
| `StringAdapter` | `String` | `TString` |
| `ByteArrayAdapter` | `ByteArray` | `TByteArray` |
| `StringListAdapter` | `List<String>` | ⚠ `TByteArray` (byte-encoded), **not** `TList<TString>` — a `genericData` slot holds an inline primitive or a composite reference, never an array |

Future composite-valued preferences (e.g. a structured "default folder" prefs
holding multiple typed fields) declare their own `Preference<MyComposite>`
with a trivial `CompositeAdapter` that passes the composite through unchanged
— no new composite needs to be registered on the realm side, since the
`VALUE` field already accepts any `Data` subtype.

Each app groups its preference declarations under one `object` per app
(`ControlPreferences`, `DrivePreferences`, future `PhotosPreferences`).
Call sites read as `service.read(ControlPreferences.WifiOnly)`.

### 5.1 Worked example: realm-wide Wi-Fi toggle

Lives in `:preference-service` (per §3.8 — Control is always installed,
so its preferences are platform vocabulary).

```kotlin
// :preference-service/.../ControlPreferences.kt
object ControlPreferences {
    val WifiOnly = Preference<Boolean>(
        id = "control.bgsync.wifiOnly",
        scope = PreferenceScope.REALM,
        default = true,
        adapter = BooleanAdapter,
    )

    val MaxMonthlyDataMb = Preference<Long>(
        id = "control.bgsync.maxMonthlyDataMb",
        scope = PreferenceScope.REALM,
        default = 500,
        adapter = LongAdapter,
    )
}
```

Drive reads `ControlPreferences.WifiOnly` directly from `:preference-service`
— no `:drive-shared` → `:control-shared` edge needed.

### 5.2 Worked example: per-device offline files

App-specific preference — lives in the owning app's shared module.

```kotlin
// :drive-shared/.../DrivePreferences.kt
object DrivePreferences {
    val OfflineFiles = Preference<List<String>>(
        id = "drive.offline.paths",
        scope = PreferenceScope.DEVICE,
        default = emptyList(),
        adapter = StringListAdapter,
    )
}
```

### 5.3 Worked example: overridable theme

Cross-app, system-wide → also lives in `:preference-service`.

```kotlin
// Future use case; declaration shape ready in v1, resolution lands when needed
// :preference-service/.../SystemPreferences.kt
object SystemPreferences {
    val Theme = Preference<String>(
        id = "system.theme",
        scope = PreferenceScope.OVERRIDABLE,
        default = "system",
        adapter = StringAdapter,
    )
}
```

Reading `read(SystemPreferences.Theme)` on a device returns: this device's
override row value (if present) → else the realm row value (if present) →
else the declaration's `default`.

### 5.4 Presentation (title / description) — deliberately *not* on `Preference`

`Preference<T>` carries no display strings. An early version bundled
`displayName` / `description` as `() -> String` lambdas, but (a) they were
never read — settings screens hardcode their own labels — and (b) a lambda
returning an English literal can't be localised; real localisation needs
Compose **string resources** (`StringResource` / `stringResource(...)`), which
the UI-free `:preference-service` has no business holding.

So presentation lives at the UI layer, keyed off `Preference.id`:

- **Today (bespoke screens):** each settings screen resolves its own
  `stringResource(Res.string.pref_<id>_title)` etc. — the natural Compose
  localisation point.
- **Follow-up (only if a *generic* settings screen lands):** if a screen ever
  renders the full preference set by querying the service
  (`PreferenceService.allPreferences()`), add a `PreferencePresentation`
  descriptor — `StringResource` refs keyed by `Preference.id`, living in
  `:ui-core` or app-shared — so presentation can travel alongside the
  declaration without polluting the service module. Premature now (3 prefs, 2
  bespoke screens); revisit when that generic UI exists. **Do not reintroduce
  display strings onto `Preference`.**

---

## 6. Service surface

Follows the canonical Tenshu service pattern (`docs/agent.md` §2) — same
shape as `NotificationService`, `FileService`, `IdentityService`:

1. **`PreferenceService : Service`** in `:preference-service` declares the
   schema + service-data relations (the typed lists the service publishes).
2. **Operations** (`WriteSetting`, `ClearSetting`) declared as their own
   `Operation` interfaces with `receiver = PreferenceService.Schema.toType()`,
   each in its own file under `:preference-service`.
3. **Suspend convenience functions** on `PreferenceService` (file-top
   alongside each `Operation` declaration) wrap the operation chain via
   `findOperation<X>(X.ID).x(...).asTaskInteractor<...>().execute()`.
4. **Typed `Preference<T>` ergonomics** live as extension functions in
   `:preference-service-client` — `service.read(pref)` / `service.set(pref, value)` —
   composing the raw chain reads + adapter conversions.
5. **`TenshuClient.preferenceService`** extension getter in
   `:preference-service-client` — `getService(PreferenceService.ID)`.

### 6.1 `PreferenceService` interface

```kotlin
interface PreferenceService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Realm-wide setting rows. One row per preference id. */
    val realmSettings: TList<RealmSetting<*>>

    /** Per-device setting rows. One row per (deviceId, preferenceId). */
    val deviceSettings: TList<DeviceSetting<*>>

    companion object {
        const val ID = "tenshu/preference/service"

        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.REALM_SETTINGS,
                    type = TList.typeOf(RealmSetting.Schema),
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_SETTINGS,
                    type = TList.typeOf(DeviceSetting.Schema),
                ),
            ),
        )
    }

    object Relations {
        const val REALM_SETTINGS = "$ID/realmSettings"
        const val DEVICE_SETTINGS = "$ID/deviceSettings"
    }
}
```

Reads from the realm- and device-settings relations use the standard chain
DSL — `service.realmSettings.search<RealmSetting<TBoolean>> { preferenceId equalTo X }.get()`
or its `.flow()` form. No special "read" operation needed; the lists are
ordinary `TList<Setting<*>>` relations that the existing search/select/flow
machinery already handles.

### 6.2 `WriteSetting` operation

Single operation that handles both `RealmSetting` and `DeviceSetting`
writes — discriminated by whether `deviceId` is present. Mirrors how
`UploadFile` / `CreateFolder` are declared in `:file-service`.

```kotlin
// :preference-service/.../WriteSetting.kt
suspend fun PreferenceService.writeSetting(
    preferenceId: String,
    value: Data,
    deviceId: String? = null,    // null = realm-wide; set = per-device override row
): TResult<Data?> =
    findOperation<WriteSetting>(WriteSetting.ID)
        .writeSetting(preferenceId, value, deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface WriteSetting : Operation {

    override val schema: OperationSchema get() = Schema

    fun writeSetting(
        preferenceId: String,
        value: Data,
        deviceId: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/preference/service/writeSetting"

        val Schema = operation(
            id = ID,
            receiver = PreferenceService.Schema.toType(),
            params = {
                string(id = Params.PREFERENCE_ID)
                composite(id = Params.VALUE)                // any Data subtype
                string(id = Params.DEVICE_ID, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PREFERENCE_ID = "$ID/preferenceId"
        const val VALUE = "$ID/value"
        const val DEVICE_ID = "$ID/deviceId"
    }
}
```

The proxy-side implementation (`WriteSettingImplementation`, `internal`,
in `:preference-service-proxy`) chooses `RealmSetting<V>` or `DeviceSetting<V>`
based on whether `deviceId` is set, constructs the entity via the relevant
`Companion.create<V>(...)`, and calls `executionContext.saveData(entity)`.
Sync fires through the standard observer pipeline.

### 6.3 `ClearSetting` operation

```kotlin
suspend fun PreferenceService.clearSetting(
    preferenceId: String,
    deviceId: String? = null,
): TResult<Data?> = /* find + execute, mirror of writeSetting */
```

Same shape as `WriteSetting`; removes the row matching `(preferenceId,
deviceId?)`. Used by `clearOverride` (§6.5) and by `Preference<T>`
deletion in the future.

### 6.4 Typed conveniences — `:preference-service-client`

The raw `writeSetting` / chain reads are usable but stringly-typed. The
typed `Preference<T>` ergonomics live as extension functions in the client
module, composing the raw API + each preference's `PreferenceAdapter<T>`.

```kotlin
// :preference-service-client/.../PreferenceServiceExtensions.kt

/** Effective value for [pref] on the current device — resolves per §6.5. */
fun <T> PreferenceService.read(pref: Preference<T>): Flow<T> = /* … */

/** Writes to the row matching pref.scope (REALM or DEVICE). OVERRIDABLE
 *  writes the realm row; use [setForDevice] for the override row. */
suspend fun <T> PreferenceService.set(
    pref: Preference<T>,
    value: T,
    context: TContext,
): TResult<Unit> = writeSetting(
    preferenceId = pref.id,
    value = pref.adapter.toData(value, context),
    deviceId = if (pref.scope == PreferenceScope.DEVICE) currentDeviceId() else null,
).fold(
    onSuccess = { TResult.Success.create(context, value = null) },
    onFailure = { TResult.Failure.create(context, error = it) },
)

suspend fun <T> PreferenceService.setForDevice(
    pref: Preference<T>,
    value: T,
    context: TContext,
    deviceId: String? = null,
): TResult<Unit> = writeSetting(
    preferenceId = pref.id,
    value = pref.adapter.toData(value, context),
    deviceId = deviceId ?: currentDeviceId(),
).fold(/* … */)

fun <T> PreferenceService.readForDevice(pref: Preference<T>, deviceId: String): Flow<T> = /* … */
fun <T> PreferenceService.readAll(pref: Preference<T>): Flow<Map<String, T>> = /* … */
suspend fun <T> PreferenceService.clearOverride(
    pref: Preference<T>,
    deviceId: String? = null,
    context: TContext,
): TResult<Unit> = clearSetting(pref.id, deviceId ?: currentDeviceId()).fold(/* … */)
```

### 6.5 Resolution rules — `read(pref)`

```
when (pref.scope) {
    REALM       -> pref.adapter.fromData(realmRow(pref.id)?.value) ?: pref.default
    DEVICE      -> pref.adapter.fromData(deviceRow(currentDeviceId, pref.id)?.value) ?: pref.default
    OVERRIDABLE -> pref.adapter.fromData(deviceRow(currentDeviceId, pref.id)?.value)
                ?: pref.adapter.fromData(realmRow(pref.id)?.value)
                ?: pref.default
}
```

Flow shape: combine the (up to two) source flows for the relevant scope and
re-emit whenever either changes.

### 6.5b Streams vs snapshots — `readOnce` / `readOnceForDevice` (as built)

`read` and `readForDevice` are **stream** reads, and a `.first()` on either is not a
snapshot:

- the relation stream emits per row, so on a realm (or device) that holds **no rows at
  all** it never emits and the `.first()` hangs; and
- consumers layer a *seed* on top for the benefit of screens — emit the declared default
  immediately so a UI renders while this device's sync identity resolves, then switch to
  the stored row (`TenshuBackupPreferences.observe`, `DeviceIdentity`). A `.first()` on a
  seeded flow returns the **placeholder**, deterministically, forever.

So every decision made *once* — a background worker's gate, a drain's inputs, a
read-modify-write — uses `readOnce(pref)` (realm) or **`readOnceForDevice(pref, deviceId)`**
(device). Both read the relation once via `getAll()` and resolve absent / undecodable /
empty to `pref.default`, so they answer immediately in every state.

⚠ This is not theoretical. Drive's Synced Folders read its selected buckets with
`selectedBuckets.first()`, therefore enumerated an empty selection on **every** drain, and
uploaded nothing at all on a device with backup enabled and a folder chosen — while
truthfully reporting `UP_TO_DATE` to Control's ingest registry. The Android background
worker had the same bug one level up (`observeEnabled().first()` → seeded `false`) and so
cancelled its own WorkManager jobs on first run. Neither was visible to tests, because the
test double returned bare `MutableStateFlow`s and so did not model the seed; it now does.

⚠ A snapshot taken immediately *after* a write can still observe the pre-write value — the
store's write mailbox is asynchronous. Reading a write back is not a valid confirmation;
screens observe, and a read-modify-write is fine because it is a read of committed state.

### 6.6 What `read` doesn't do

- **No write-validation that the value matches the type.** If the row's
  `Data` doesn't match the adapter's expected wrapper (e.g. the stored
  value is a `TString` but the `Preference<Boolean>` adapter expects a
  `TBoolean`), `pref.adapter.fromData(...)` returns null and `read` falls
  back to `pref.default`. The service logs the type mismatch. This is the
  same trust model as the Operation request layer — the proxy doesn't know
  the typed schema.
- **No cross-preference invariants.** Each pref is independent. Validation
  spanning multiple prefs (e.g. "Wi-Fi-only false implies max-data > 0") is
  the consumer's job.

---

## 7. Override semantics (`OVERRIDABLE`)

In v1 the storage shape supports `OVERRIDABLE` (read path combines realm + per-device rows; write APIs distinguish realm vs override). v1 does **not** ship any preference that uses it — every declared preference is `REALM` or `DEVICE`. The first `OVERRIDABLE` user pulls the resolution logic out of "spec'd" into "exercised" and motivates the related UX (clear-override action, "this device differs from realm default" indicator).

If a v1 reader has questions ("what happens if a realm row is written while a per-device override exists?"), the answer is: nothing changes; the per-device override continues to win. The override is *opaque* to the realm row.

---

## 8. Module layout

```
preference-service/                            # PreferenceService + schemas + operations + Preference<T> + adapters
preference-service-common/                     # DTOs + DataIdProvider traits + tModule registration
preference-service-client/                     # ROs + TenshuClient + PreferenceService extensions
preference-service-proxy/                      # OperationImplementations (`internal`)
preference-service-test-utils/                 # createPreferenceProxy fixture
```

Symmetric with `:identity-service`, `:file-service`, `:notification-service`,
`:sync-service`. Identical wiring through `core/parser/ModuleConfiguration`,
`SchemaRegistry`, the proxy DI graph, and the test harness.

### 8.1 Where preferences are declared

Two rules (per §3.8):

- **Control's preferences live in `:preference-service`** — `ControlPreferences`
  object, alongside the `Preference<T>` class itself. Control is always
  installed, so its declarations are part of the platform vocabulary and
  any consumer can reference them without a dep on `:control-shared`.
- **App-specific preferences live in `:<app>-shared`** — `DrivePreferences`
  in `:drive-shared`, future `PhotosPreferences` in `:photos-shared`, etc.
  A consumer that wants to read an app-specific pref takes a dep on that
  app's shared module.

Cross-app system preferences (a theme that applies across every Tenshu app)
also live in `:preference-service` under `SystemPreferences`.

| Pref | Lives in | Read from |
|---|---|---|
| `ControlPreferences.WifiOnly` | `:preference-service` | Drive (settings wheel), Control (settings UI + scheduler) |
| `ControlPreferences.MaxMonthlyDataMb` | `:preference-service` | Control scheduler |
| `DrivePreferences.OfflineFiles` | `:drive-shared` | Drive's offline-keeper |
| `SystemPreferences.Theme` (future) | `:preference-service` | `:ui-core` |

---

## 9. Worked end-to-end — Wi-Fi-only toggle

1. **Declaration.** `:preference-service` declares `ControlPreferences.WifiOnly`
   (`scope = REALM, default = true`).
2. **Control Settings UI** binds to `PreferenceService.read(ControlPreferences.WifiOnly)` —
   shows a checkbox at the current value. Tap toggles via
   `service.set(ControlPreferences.WifiOnly, false)`.
3. **Drive search-bar settings wheel** reads the same flow via the proxy.
   Updates ride the proxy's regular Stream channel; the wheel reflects the
   change instantly.
4. **Persistence.** Set fires `executionContext.saveData(RealmSetting.create<TBoolean>(ctx, preferenceId = "control.bgsync.wifiOnly", value = BooleanAdapter.toData(false, ctx)))` — typed as `RealmSetting<TBoolean>`, the `value` field is statically `TBoolean`.
5. **Sync.** `SyncObserver` emits one `SyncBlock`; paired devices apply on
   their next drain.
6. **Scheduling.** Control proxy's `ScheduleService` (Phase 5 of bgproc)
   reads `ControlPreferences.WifiOnly` at `schedule(...)` time and emits
   `ConstraintSpec(network = if (wifiOnly) UNMETERED else ANY)`. Re-schedule
   on toggle = `service.read(ControlPreferences.WifiOnly)` is the source flow
   for the ScheduleService's job registration.

---

## 10. Relation to in-flight tracks

### 10.1 `core-background-processing.md`

The Wi-Fi-only + max-data preferences are consumed by Phase 5
(`ScheduleService` + Android binding). Net effect on the bgproc proposal:

- `core-background-processing.md` §3.3 currently lists `network: ANY |
  UNMETERED | NONE` in `ConstraintSpec`. **No change needed** — the
  preference resolves *into* that constraint at schedule time.
- `core-background-processing.md` §3.3 has **no `maxDataMb` field** — adding
  one is required for this proposal's max-data preference to have a
  consumer. That's a small bgproc amendment, not a rewrite (one new field
  on `ConstraintSpec`, app-level bookkeeping in the Android `ScheduleService`
  binding since WorkManager doesn't expose a "max bytes" constraint
  natively). The amendment lands in the bgproc proposal in the same PR
  that lands this one.

### 10.2 `sync-merge-strategies.md`

`RealmSetting` / `DeviceSetting` declare `LastWriterWins`. Once
`MergerRegistry` lands they register as standard entries — no special-case
merger. No conflict with that proposal.

### 10.3 `core-realm-data.md`

The Realm Data metamodel (Composite annotation for "data types surfaced by
Tenshu") does **not** mark `RealmSetting`/`DeviceSetting` as realm data —
preferences are configuration, not user data. The Realm Data inventory
operations (when they land) should skip the `:preference-service` composites.

### 10.4 `MasterKeyStore` / `LocalStore` cleanup

Adjacent storage cleanup tracked in
[`docs/tech-debt.md`](../tech-debt.md) under
"`SecureStorageDelegate` — Split + Drop Deprecated `EncryptedSharedPreferences`":
`SecureStorageDelegate` shrinks to "secrets only" + renames to `MasterKeyStore`
(Android backend rebuilds on AndroidKeyStore + DataStore, drops the deprecated
`EncryptedSharedPreferences`), and `DEVICE_ID` / `LAST_SYNC_TIMESTAMP` move to
a new `LocalStore` abstraction for the narrow set of state that *can't* sync
(device-identity bootstrap, polling cursor) or *shouldn't* (high-frequency
ephemera like scroll position). Per-device user-meaningful preferences belong
in `DeviceSetting<V>` here, not in `LocalStore` — see §3.9.

This `PreferenceService` proposal does **not** include any of that work; the
two cleanups are orthogonal and neither blocks the other.

---

## 11. Threat-model impact

Preferences are realm data and go through the same encryption envelope as
every other `Syncable` composite. No new wire shape, no new key material,
no new server-side trust assumption.

One sentence amendment to `docs/sync/threat-model.md` §A4 (Data Privacy
Boundary): preferences (`RealmSetting`, `DeviceSetting`) are realm-private
ciphertext same as Files/Folders/Devices/Notifications. The relay sees
ciphertext only.

No new asset class. No new T-* entry.

---

## 12. Open questions

### 12.1 Default-value provenance

When `read(pref)` returns `pref.default` because no row exists yet, should
the service auto-write the default row? Two paths:

- **(a) Lazy** — never write a default row; `read` reports the default
  in-memory. Pro: smaller realm. Con: a UI surface "this preference is
  set to default" looks the same as "no row exists yet" — surfacing
  divergence requires another flag.
- **(b) Eager** — first `read` writes a default row. Pro: every preference
  always has a stored value, simpler debugging. Con: chatty on first run;
  every paired device emits a default row for every preference it reads.

Recommend **(a) Lazy** — defaults live in code, not the realm.

### 12.2 Per-app PreferenceService instance vs single shared

Should `PreferenceService` be a single proxy-side service that all apps
share, or one instance per app (`controlPreferenceService`,
`drivePreferenceService`)? Single shared is simpler (one DI binding, one
SyncObserver) and the per-app scoping is achieved by the preference id
namespace (`control.*`, `drive.*`). Per-app would mean each app's
preferences only sync between paired devices that ALSO have that app
installed — but Tenshu has no concept of "this app is installed on this
device" today, so this is moot until that concept exists.

Recommend **single shared service**. App-namespaced preference ids are
sufficient.

### 12.3 Preference deletion semantics

Should there be a `service.delete(pref, deviceId? = null)` that emits a
tombstone? Use case: "I changed my mind, the device override should be
gone, fall back to realm." This is `clearOverride`, which is in the surface
(§6). A more general "delete the preference row entirely" — same effect as
`set(pref, pref.default)` if we go with lazy defaults (12.1), so nothing
extra needed.

If we go eager (12.1), we'd need a real delete to distinguish
"explicitly default" from "never set." See 12.1.

### 12.4 Read-side type mismatch

What if `RealmSetting.value` carries a `Data` of the wrong concrete type
(e.g. a `TString` where the `Preference<Boolean>`'s `BooleanAdapter` expects
a `TBoolean`)? Today's proxy doesn't know the typed declaration. Options:

- **(a) Fall back to default + log** (current §6.6) — adapter's `fromData`
  returns null, `read` returns `pref.default`.
- **(b) Throw to the SDK caller.** Worse — leaves the UI in an
  unrecoverable state.

Recommend **(a)**, possibly with a sentinel value in the `Flow` that lets
the consumer detect the mismatch if it wants to. Not the v1 default.

### 12.5 Migration from `SecureStorageDelegate.storeString`

`DEVICE_ID` and `LAST_SYNC_TIMESTAMP` are **not** preferences. They're the
narrow class of state that genuinely *can't* sync — `DEVICE_ID` is the
device's own cryptographic identity (chicken-and-egg with the pairing
flow that assigns it), `LAST_SYNC_TIMESTAMP` is the per-device polling
cursor (no semantic meaning on another device). They move to `LocalStore`
(separate proposal), not to `PreferenceService`. v1 of this proposal does
not touch the existing `SecureStorageDelegate` call sites.

Per-device *user-meaningful* state (e.g. "files kept offline on this
device") is **not** in this exclusion — it belongs in `DeviceSetting<V>`
where it syncs and survives device replacement / recovery (§3.9).

---

## 13. Phased plan

The phases are independent enough to ship one per PR.

### Phase 1 — Module scaffolding + schemas

`:preference-service` (+ `-common` / `-client` / `-proxy` / `-test-utils`)
created, `RealmSetting` + `DeviceSetting` schemas registered, `Preference<T>`
abstract class + scope enum + `PreferenceAdapter<T>` interface in
`:preference-service`. Built-in adapters for the primitive types
(`BooleanAdapter`, `LongAdapter`, `IntAdapter`, `StringAdapter`,
`ByteArrayAdapter`, `StringListAdapter`). No PreferenceService implementation
yet — just the data model.

DoD: schemas appear in `SchemaRegistry`, `Preference<Boolean>` etc. compile,
round-trip `toData` / `fromData` tests pass for each built-in adapter.

### Phase 2 — `PreferenceService` implementation (REALM + DEVICE only)

`PreferenceServiceImpl` in `:preference-service-proxy` with `read` / `set` /
`setForDevice` / `readForDevice` / `readAll`. `OVERRIDABLE` scope **accepts
writes** but `read` falls back to the realm row only (no override resolution
yet). The `OVERRIDABLE` resolution path is gated behind a TODO + test
expectation.

DoD: a SystemTest writes a `RealmSetting` on host, asserts joiner reads it;
ditto `DeviceSetting`; both via the SDK chain.

### Phase 3 — Control Background Sync Settings screen

`ControlPreferences.WifiOnly` + `ControlPreferences.MaxMonthlyDataMb` declared
in `:preference-service` (per §3.8 — Control is always installed).
`:control-shared/.../settings/backgroundsync/BackgroundSyncSettingsScreen.kt`
binds to both. A second row added to `SettingsScreen.kt`. No ScheduleService
consumer yet — values just persist. The bgproc max-data amendment lands in
the same PR.

DoD: SettingsScreenTest covers the new row; toggling persists across
app restart.

### Phase 4 — Drive's offline-keeper consumer — **LANDED**

`DrivePreferences.OfflineFiles` declared in `:drive-shared` (per §3.8 / §8.1 —
app-specific prefs live in the owning app's shared module). DEVICE-scoped
`Preference<List<String>>` over `StringListAdapter`.

**As built:**

- **Device-id resolution.** Phase 4 is the first end-to-end DEVICE-scoped
  consumer. `set`/`read` reject DEVICE scope; `setForDevice`/`readForDevice`
  need an explicit device id, and the SDK has no `currentDeviceId` resolver
  (Phase 1+2 deviation). Drive resolves it the same way Control does
  (`TenshuSyncRepository.getDeviceId`): `client.syncService.getSyncStatus()
  .deviceId`. This is why `:drive-shared` gains a `:sync-service*` dependency.
  A small `OfflinePreferences` (`:drive-shared/data`) caches the id and wraps
  `readForDevice` / `setForDevice`.
- **Pin/unpin UI.** The pre-existing "Make available offline" toggle in
  `MultiSelectBottomSheet` (previously a no-op stub) now writes the selected
  paths through `SelectionViewModel.onOfflineToggled`; the switch reflects
  whether the whole selection is pinned (read-modify-write on the single
  per-device list row).
- **Settings list.** `SyncPreferencesScreen` gains a "Files kept offline"
  section listing the pinned files with a per-row remove (unpin) action.
- **Scope note.** Phase 4 is the *preference plumbing + pin UI* only. Actually
  retaining the file bytes offline (the byte-level offline-keeper) is a
  separate background concern, naturally adjacent to bgproc Phase 5 — out of
  scope here.

DoD: pinning a file via the Drive UI persists and is visible from a paired
device. Cross-device propagation rides the existing `DeviceSetting`
`Syncable(Eager)` path already proven by `PreferenceSyncSystemTest`
(`joinerSdkChain_findsHostWrittenDeviceSetting`); the Drive client→proxy
round-trip is covered by `OfflinePreferencesIntegrationTest`. Durable
persistence rides the realm's `ObjectStore` (SqliteObjectStore in prod), same
as every other synced row.

Verified: `:drive-shared:desktopTest` (incl. `OfflinePreferencesIntegrationTest`
+ `SyncPreferencesScreenTest`) + `:drive-desktop:compileKotlinJvm`. Android
variant unverified (no Android SDK in build env); the new deps are commonMain
KMP modules and Android wires the same DI via `filesRepositoryModule`'s
`includes(domainModule)`.

### Phase 5 — Scheduler integration — **resolver LANDED; enforcement gated on bgproc Phase 6**

bgproc Phase 5 (`ScheduleService` + `Schedule`/`ConstraintSpec`/`ScheduleItem`
+ in-process `ScheduleDrainLoop`) **already landed**, so the gate is
satisfied. This phase's preference-side consumer is now built:

- **`ConstraintSpec.maxDataMb`** — the §3.3 field finally exists in code
  (optional `TInteger`), the consumer for `ControlPreferences.MaxMonthlyDataMb`.
- **`backgroundSyncConstraints()`** (`:control-shared/.../data/BackgroundSyncConstraints.kt`)
  — a pure `backgroundSyncConstraintSpec(wifiOnly, maxMonthlyDataMb)` mapping
  (`WifiOnly → network UNMETERED/ANY`, `MaxMonthlyDataMb → maxDataMb`, 0 = no
  cap) plus a reactive `PreferenceService.backgroundSyncConstraints(): Flow<ConstraintSpec>`
  that `combine`s the two realm prefs and re-emits on any change (seeding each
  leg's declared default via `onStart`, since `read` stays silent on cold start).
  This is the "source flow for the scheduler's job registration" (§9 step 6).
  It lives in `:control-shared` — the one module depending on both
  `:preference-service` (where `ControlPreferences` live, §3.8) and
  `:sync-service` (where `ConstraintSpec` lives) — keeping that edge out of the
  service modules.

**Call-site mechanism (desktop-verifiable slice) — LANDED, test-only.**
`BackgroundSyncScheduler` (`:sync-service-proxy`) reconciles a single periodic,
head-less `ScheduleItem` (dedup key `"sync-drain"`) with a `Flow<ConstraintSpec>`:
each emission re-runs the `Schedule` op, which coalesces on the dedup key, so a
preference toggle reschedules the one pending item under the new constraint. It's
the registration counterpart to `ScheduleDrainLoop` and (like the rest of the
schedule machinery, since `ScheduleService` has no client RO yet) registers via
`executeAsync(Schedule.ID, …)`. Proven by `:sync-service-proxy:jvmTest`
`BackgroundSyncSchedulerTest` (UNMETERED → ANY reschedule in place; drain-loop
dispatch). Production wiring passes `preferenceService.backgroundSyncConstraints()`
as the flow and the real sync-drain op as `opId`.

**Still gated on bgproc Phase 6 (PlatformScheduler / Android WorkManager):**
the call-site mechanism exists but is **not wired into production proxy init** —
doing so would run a second sync driver alongside the legacy `SyncScheduler`
poll (double-drain) until the two are unified — there is **no real head-less
sync-drain op yet** (the only `HeadlessSafe` op is a test fake; `SyncScheduler`'s
drain methods are `internal`, not operations — exposing them is the Phase 1
`SyncScheduler → BackgroundJobRegistry` extraction), and `ConstraintSpec` is only
*enforced* by the Android scheduler binding (Phase 6, not started; other
platforms treat it as advisory, §3.3/§5.6).

The enforcement plan — `SyncScheduler`-vs-`ScheduleService` roles, per-platform
model, and the cheap desktop-shippable first step (**Path A**: gate the
in-process `SyncScheduler` on this constraint, no `ScheduleService` involved) —
is in [`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md). **Path A has
landed** (`SyncGate` + `ConstraintSyncGate` + `NetworkStateProvider`): `WifiOnly`
now suspends the in-process sync cycle on a metered network — the first real
*enforcement* of these preferences (Android-effective; desktop is assumed
unmetered so the gate is inert there). App-closed / `maxDataMb` enforcement
remains bgproc Phase 6.

Verified: `:control-shared:desktopTest` `BackgroundSyncConstraintsTest`
(pure mapping incl. `maxDataMb`; reactive re-emit when `WifiOnly` toggled) +
`:sync-service-proxy:jvmTest` `BackgroundSyncSchedulerTest` + `ScheduleServiceTest`.

DoD (original): "toggle Wi-Fi-only on, verify the scheduler registers a
`network = UNMETERED` constraint" — constraint *sourcing* is covered by
`BackgroundSyncConstraintsTest` and the *registration/reschedule* by
`BackgroundSyncSchedulerTest`; the real sync-drain op, production wiring, and
end-to-end enforcement move to bgproc Phase 6.

### Phase 6 — `OVERRIDABLE` resolution

When a concrete consumer asks for it. Read-side combines the two flows
and resolves per §6.5. `clearOverride` becomes wired. Not on the critical
path — held until the first OVERRIDABLE preference shows up.

---

## 14. Implementation notes for Claude Code

(Following the §5b "Implementation Notes for Claude Code" convention used
across the in-flight proposal docs — file paths + before/after sketches per
phase.)

### 14.1 Phase 1 — Module scaffolding

**New files:**

Module layout follows the canonical Tenshu service shape (per agent.md §2 /
`NotificationService`):

```
preference-service/                   # interfaces, schemas, operations
  PreferenceService.kt                # Service interface + Schema + Relations (§6.1)
  Setting.kt                          # super-composite (§4.1)
  RealmSetting.kt                     # concrete (§4.2)
  DeviceSetting.kt                    # concrete (§4.3)
  WriteSetting.kt                     # Operation + suspend convenience (§6.2)
  ClearSetting.kt                     # Operation + suspend convenience (§6.3)
  Preference.kt                       # Preference<T> abstract class + PreferenceScope
  PreferenceAdapter.kt                # interface + built-in adapters
  ControlPreferences.kt               # ControlPreferences object (§5.1)
  SystemPreferences.kt                # for future cross-app prefs (§5.3)

preference-service-common/            # DTOs, common impl pieces
  impl/SettingDto.kt
  impl/RealmSettingDto.kt
  impl/DeviceSettingDto.kt
  impl/WriteSettingDto.kt
  PreferenceServiceCommonModule.kt    # tModule { } registering schemas + DTO factories
  trait/RealmSettingDataIdProvider.kt
  trait/DeviceSettingDataIdProvider.kt

preference-service-client/            # ROs + TenshuClient extensions
  impl/PreferenceServiceRo.kt
  impl/WriteSettingRo.kt
  impl/ClearSettingRo.kt
  PreferenceServiceClientExtensions.kt   # `val TenshuClient.preferenceService`
  PreferenceServiceExtensions.kt         # typed read/set/setForDevice/... (§6.4)
  PreferenceServiceClientModule.kt       # tModule { } registering RO factories

preference-service-proxy/             # OperationImplementation (`internal`)
  impl/WriteSettingImplementation.kt
  impl/ClearSettingImplementation.kt
  PreferenceServiceProxyModule.kt     # tModule { } registering OperationImplementationFactory

preference-service-test-utils/        # fixtures
  createPreferenceProxy.kt            # mirror of createFileProxy
```

**Schema shape** — see §4.1 / §4.2 / §4.3 for the canonical
`val Schema = composite(...)` declarations. Identical convention to
`File.Schema` / `Folder.Schema` (the `docs/schema-dsl.md` standard);
no new DSL, no new annotation.

`RealmSettingDataIdProvider` is a one-line subclass of
`CompositeDataIdProvider<RealmSetting<*>>` keyed on
`Setting.Field.PREFERENCE_ID` — direct mirror of `FileDataIdProvider`.
`DeviceSettingDataIdProvider` keys on the `(DEVICE_ID, PREFERENCE_ID)` pair —
if `CompositeDataIdProvider`'s current shape only accepts a single key
field, the path of least resistance is a derived
`compositeKey = "$deviceId/$preferenceId"` field on the entity (set in
`DeviceSetting.create`) and key on that. Investigate during Phase 1; if a
multi-field key provider is cheap to add it's arguably the cleaner answer.

### 14.2 Phase 2 — `WriteSettingImplementation` + chain reads

The write path is one `OperationImplementation`: dispatch on the `deviceId`
param (null = `RealmSetting<V>`, present = `DeviceSetting<V>`), construct
via the relevant `Companion.create<V>(...)`, call
`executionContext.saveData(entity)`. `SyncObserver` fires natively.

Read paths reuse the standard chain DSL on the service-data relations —
`service.realmSettings.search<RealmSetting<TBoolean>> { preferenceId equalTo X }.flow()`.
No new resolver code. The §6.4 client extensions glue these together with
the `PreferenceAdapter<T>` round-trip.

**Test fixtures:** `:preference-service-test-utils` exposes
`createPreferenceProxy(scope)` for full-proxy round-trip tests
(mirror `createFileProxy` in `:file-service-test-utils`).

### 14.3 Phase 3 — Control Settings UI

Files to add:

- `preference-service/src/commonMain/kotlin/org/tenshu/core/preference/ControlPreferences.kt` (declares `WifiOnly` + `MaxMonthlyDataMb`)
- `control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/settings/backgroundsync/BackgroundSyncSettingsScreen.kt`
- `control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/settings/backgroundsync/BackgroundSyncSettingsViewModel.kt`

`SettingsScreen.kt` gains one `AppListItem` for "Background sync" navigating
to the new screen. Tests mirror `SettingsScreenTest.kt`. `:control-shared`
takes a dep on `:preference-service` (already present transitively in most
modules; verify before this PR).

bgproc proposal amendment: extend `ConstraintSpec` with `maxDataMb: Long?`
and document the per-platform mapping (Android: app-level rolling counter;
others: ignored in v1).

---

## 15. Worked migrations table

| Today | Tomorrow |
|---|---|
| User wants Wi-Fi-only toggle: nothing exists | Add `ControlPreferences.WifiOnly` in `:preference-service`; `BackgroundSyncSettingsScreen` (`:control-shared`) reads + writes via `PreferenceService` |
| User wants to pin a file offline: nothing exists | Add `DrivePreferences.OfflineFiles` in `:drive-shared`; Drive's offline-keeper reads the per-device value |
| Adding a new preference (any) | Add a `Preference<T>` entry to `ControlPreferences` / `<App>Preferences` / `SystemPreferences` (per §8.1's rule); use a built-in `PreferenceAdapter<T>` or define one (composites pass themselves through unchanged); add a row to the relevant Settings UI |
| `SecureStorageDelegate.storeString("DEVICE_ID", ...)` | Unchanged in this proposal; `DEVICE_ID` + `LAST_SYNC_TIMESTAMP` move to `LocalStore` (separate doc) — they can't sync. *Per-device preferences* go to `DeviceSetting<V>` here, where they sync and survive recovery (§3.9). |
| `EncryptedSharedPreferences` on Android | Unchanged in this proposal; replaced by AndroidKeyStore + DataStore in the `MasterKeyStore` proposal |
