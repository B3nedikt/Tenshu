# Settings-projection maintainer — generalising the relation plumbing

**Status:** sketch (no code). Follow-up to the first-class `fileSyncSettings`
relation (`docs/proposals/drive-offline-online.md` §4.10).

**Owner module (proposed):** `:preference-service-proxy`.

---

## 1. Motivation

We now have three proxy-side `DataObserver`s that all do the same thing — *keep a
relation array in sync with preference-setting rows* — but each re-implements the
plumbing:

| maintainer | module | source type | filter | grouped by | array element |
|---|---|---|---|---|---|
| `PreferenceRelationMaintainer` (`realmSettings`) | `:preference-service-proxy` | `RealmSetting` | all | the row itself | the `RealmSetting` (mirror) |
| `PreferenceRelationMaintainer` (`deviceSettings`) | `:preference-service-proxy` | `DeviceSetting` | all | the row itself | the `DeviceSetting` (mirror) |
| `FileSyncSettingsRelationMaintainer` | `:file-service-proxy` | `DeviceSetting` | `file.sync.*` | `deviceId` | a derived `FileSyncSetting` (fold) |

`FileSyncSettingsRelationMaintainer` is **~90 % generic plumbing and ~8 lines of
fold**. The generic part — observe saves/deletes of a setting type, filter by a
preference-id set, group rows into a projection key, read the group's current rows
from the DB, upsert/remove the projection in the relation array (idempotent
remove-then-add), recover the key from a deleted row — is exactly what
`PreferenceRelationMaintainer` does too, in its degenerate *mirror* form.

The only irreducibly per-relation piece is the **fold**: which preference ids feed
the projection, and how their values map into the typed element. Everything else is
boilerplate we should write once.

This is not a rule-of-three guess: **three real shapes exist today** and a base
validates against all of them (two mirrors + one fold).

## 2. Non-goals

- **Not** a declarative end-to-end generator. The maintainer is only one of ~6
  repetitive pieces per typed settings-relation (schema, `Dto`, `DataIdProviderImpl`,
  `Ro`, maintainer, client accessor). Collapsing *all* of that into a single
  `settingsProjection(...)` registration is a larger platform feature — noted in §6,
  explicitly out of scope here.
- **Not** a change to the wire/sync protocol. These projections stay derived,
  non-`Syncable`; the source `Setting` rows remain the synced source of truth.
- **Not** a new merge strategy. No threat-model surface.

## 3. Proposed shape

A base `DataObserver` in `:preference-service-proxy` that owns the lifecycle, with
the variation expressed as four small hooks. Keys are plain `String`
(`deviceId`, or a setting's own composite key).

```kotlin
// :preference-service-proxy
abstract class SettingsProjectionMaintainer(
    protected val executionContext: ExecutionContext,
    private val sourceTypeId: String,      // RealmSetting.ID | DeviceSetting.ID
    private val relationId: String,        // the projection relation id
    private val elementSchemaId: String,   // the array element's schema id
) : DataObserver {

    private val relationRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(executionContext.context, TList.ID, relationId),
        isEntity = false,
        elementSchemaId = elementSchemaId,
    )

    // ---- the four hooks a subclass fills in ----

    /** Preference ids that feed this projection; `null` = every row of [sourceTypeId] (mirror). */
    protected abstract val sourcePreferenceIds: Set<String>?

    /** The projection key a saved source row contributes to (e.g. its `deviceId`, or its own composite key). */
    protected abstract fun groupKeyOf(setting: Data): String?

    /** Recover the projection key from a deleted source row's id (inverse of the save path). */
    protected abstract fun groupKeyOf(deletedId: DataId.StringDataId): String?

    /** The source rows that belong to [key] — the DataIds to read before projecting. */
    protected abstract fun sourceRowIds(key: String): List<DataId.StringDataId>

    /** The stable DataId of the projection row for [key]. */
    protected abstract fun projectionDataId(key: String): DataId.StringDataId

    /** Build the element to store from the group's current rows (keyed by preferenceId), or `null` to drop it. */
    protected abstract fun project(key: String, rows: Map<String, Data>): Data?

    // ---- generic, final lifecycle ----

    final override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isOfSourceType()) return
        if (sourcePreferenceIds?.contains(preferenceIdOf(data)) == false) return
        rebuild(groupKeyOf(data) ?: return)
    }

    final override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val id = dataId as? DataId.StringDataId ?: return
        if (id.type.get() != sourceTypeId) return
        rebuild(groupKeyOf(id) ?: return)
    }

    private fun rebuild(key: String) {
        val rows: Map<String, Data> = sourceRowIds(key)
            .mapNotNull { executionContext.database.getData(it)?.let { row -> preferenceIdOf(row) to row } }
            .toMap()
        val element = project(key, rows)
        val projectionId = projectionDataId(key)
        executionContext.database.transaction {
            executionContext.database.removeFromArray(relationRef, projectionId)
            if (element != null) executionContext.database.addToArray(relationRef, element)
        }
    }
    // + the shared helpers: isOfSourceType (isSubtypeOf), preferenceIdOf, string-field reads.
}
```

### How each of the three maps on

**`fileSyncSettings` (fold)** — the whole maintainer collapses to:

```kotlin
class FileSyncSettingsMaintainer(ctx) : SettingsProjectionMaintainer(
    ctx, DeviceSetting.ID, FileSyncSetting.RELATION, FileSyncSetting.ID,
) {
    override val sourcePreferenceIds = setOf(FileSyncPreferences.DefaultPolicy.id, FileSyncPreferences.Overrides.id)
    override fun groupKeyOf(s: Data) = s.deviceId            // the device
    override fun groupKeyOf(id: StringDataId) = id.keyValue.get().substringBeforeLast("/").takeIf { … }
    override fun sourceRowIds(deviceId) = sourcePreferenceIds.map { StringDataId(DeviceSetting.ID, compositeKey(deviceId, it)) }
    override fun projectionDataId(deviceId) = StringDataId(FileSyncSetting.ID, deviceId)
    override fun project(deviceId, rows): Data? {
        val def = rows[DefaultPolicy.id]?.value?.let(SyncPolicyAdapter::fromData)
        val ovr = rows[Overrides.id]?.value?.let(PathSyncOverrideSetAdapter::fromData)
        if (def == null && ovr == null) return null
        return FileSyncSetting.create(ctx, deviceId, def ?: DefaultPolicy.default, ovr ?: Overrides.default)
    }
}
```

**`realmSettings` / `deviceSettings` (mirror)** — the degenerate case: no filter, the
key is the row's own id, the element is the row itself.

```kotlin
class RealmSettingsMaintainer(ctx) : SettingsProjectionMaintainer(
    ctx, RealmSetting.ID, PreferenceService.Relations.REALM_SETTINGS, RealmSetting.ID,
) {
    override val sourcePreferenceIds = null                       // every RealmSetting
    override fun groupKeyOf(s: Data) = s.preferenceId            // its own identity key
    override fun groupKeyOf(id: StringDataId) = id.keyValue.get()
    override fun sourceRowIds(key) = listOf(StringDataId(RealmSetting.ID, key))
    override fun projectionDataId(key) = StringDataId(RealmSetting.ID, key)
    override fun project(key, rows) = rows.values.firstOrNull()  // mirror: store the row itself
}
```

`PreferenceRelationMaintainer` becomes two such instances (`initPreferenceService`
registers both). The `DeviceSetting` variant keys on `compositeKey` instead of
`preferenceId`.

## 4. Axes of variation (the design's load-bearing choices)

1. **Filter** — `sourcePreferenceIds: Set<String>?` (`null` = mirror-all).
2. **Grouping** — `groupKeyOf` decides *mirror* (key = the row's own id, 1 row per
   key) vs *fold* (key = a shared attribute like `deviceId`, N rows per key). This is
   the single knob that distinguishes the two existing shapes.
3. **Source read** — `sourceRowIds(key)` enumerates the DataIds to fold; for mirror
   it is `[key]`, for fold it is `[compositeKey(key, prefId) … ]`.
4. **Projection** — `project(key, rows)` returns the element to store (the row itself
   for mirror; a derived entity for fold) or `null` to drop the row.

Everything else (the observer wiring, idempotent upsert, remove-when-empty,
delete→rebuild) is fixed.

## 5. Decisions to lock before coding

- **Retrofit scope.** Validating the base against all three shapes means touching the
  merged, sync-critical `PreferenceRelationMaintainer`. Options: (a) introduce the
  base + only the *new* `FileSyncSettings` subclass (low blast radius, abstraction
  validated against one fold + read against the mirror by inspection); (b) also
  retrofit both preference mirrors (proves the abstraction against three real shapes,
  deletes the most code, but re-tests sync-visibility plumbing). **Recommendation:
  (b)** — the whole point is de-duplication, and the mirror retrofit is what
  *proves* the grouping knob is right. Gate it on the existing
  `PreferenceSyncSystemTest` + a mirror unit test staying green.
- **Module home.** `:preference-service-proxy` (it owns the `Setting` domain;
  `:file-service-proxy` already depends on it). The base is `public`; subclasses live
  in each consumer module.
- **Bare-composite vs entity element.** Keep the `fileSyncSettings` relation's finding: fold elements are
  **bare composites** (no `Entity.create`/`saveData`); readers extract `.value`
  through `TResult.Success<*>`, not `Entity<T>`. The base's `addToArray` takes
  whatever `project` returns, so mirror (entity) and fold (composite) both work
  unchanged.
- **`getData` cost.** `rebuild` re-reads the group's rows on every save. For the
  2-row fold this is trivial; if a future projection folds many rows it is still
  O(group), fired once per source write. Acceptable; note it.

## 6. Bigger play (out of scope, noted)

The maintainer is one of ~6 repetitive pieces. A future `settingsProjection(...)`
schema-DSL registration could generate the schema marker, `Dto`, `DataIdProviderImpl`,
`Ro`, the cross-config `serviceDataRelation`, *and* register the maintainer — reducing
a new typed settings-relation to a field list + a fold function. That is a real
platform feature deserving its own proposal; this sketch deliberately generalises only
the maintainer, which is the highest-ratio (90/10) duplication and a safe first step.

## 7. Suggested slicing

1. **Base + new subclass.** Introduce `SettingsProjectionMaintainer`; reimplement
   `FileSyncSettingsRelationMaintainer` on top of it. Net-neutral behaviour; the
   existing `FileSyncSettingsRelationMaintainerTest` + system test are the guardrail.
2. **Mirror retrofit.** Re-express `PreferenceRelationMaintainer` as two base
   instances in `initPreferenceService`; delete the old class. Guarded by
   `PreferenceSyncSystemTest` + a focused mirror unit test.
3. *(optional, later)* the §6 declarative helper — separate proposal.
