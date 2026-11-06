package org.tenshu.preference

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * Abstract super-composite for every stored preference row. Concrete subtypes
 * are [RealmSetting] (one row per preference, realm-wide) and [DeviceSetting]
 * (one row per `(deviceId, preferenceId)` pair).
 *
 * The stored [value] is a **polymorphic `Data` field** — the concrete Tenshu
 * type (`TBoolean`, `TInteger`, `TString`, `TList`, a composite, ...) is chosen
 * by the [Preference] declaration's [PreferenceAdapter] and is self-describing on
 * the wire, so any consumer (including another app, e.g. Control) can read it
 * through the schema without the owning app's adapter. Consumers normally read
 * through `pref.adapter.fromData(entity.value)` to get a typed `T`.
 *
 * (The earlier `RealmSetting<V>` design used a `typeVar`-typed value field, which
 * the DB dbEntryMapper can't resolve; the interim fix stored an opaque
 * `TByteArray`. A concrete `Data.Schema` field round-trips fine — the sync
 * engine's own `DataBlockPayload.value` is the same shape — so the value is now
 * real `Data`. See `docs/proposals/core-preference-service.md`.)
 *
 * `Setting` carries no traits and declares no fields — the schema acts as a
 * type marker. The `preferenceId` + `value` declarations live on the concrete
 * subtypes so they share field-ids without colliding with the platform's
 * `DataFactory.collectFields` field-inheritance walk (a known double-counting
 * hazard when a parent already contributes fields).
 */
interface Setting : Composite {

    override val schema: CompositeSchema get() = Schema

    val preferenceId: TString
        get() = getField(Field.PREFERENCE_ID)

    val value: Data
        get() = getField(Field.VALUE)

    companion object {
        const val ID = "tenshu/preference/setting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = { } // field declarations live on the concrete subtypes
        )
    }

    object Field {
        const val PREFERENCE_ID = "$ID/preferenceId"
        const val VALUE = "$ID/value"
    }
}

val Entity<out Setting>.preferenceId: TString
    get() = getFieldValue(Setting.Field.PREFERENCE_ID)

val Entity<out Setting>.value: Data
    get() = getFieldValue(Setting.Field.VALUE)
