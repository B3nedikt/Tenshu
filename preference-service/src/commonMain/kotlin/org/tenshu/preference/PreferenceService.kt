package org.tenshu.preference

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service surface for synced per-app / per-device settings.
 *
 * Exposes two relations — [realmSettings] (one row per preference id,
 * realm-wide) and [deviceSettings] (one row per `(deviceId, preferenceId)`
 * pair) — that consumers traverse with the standard chain DSL:
 *
 * ```
 * service.realmSettings
 *     .search<RealmSetting> { this.preferenceId equalTo X }
 *     .get()                                   // one-shot read
 *
 * service.realmSettings
 *     .search<RealmSetting> { this.preferenceId equalTo X }
 *     .flow()                                  // reactive
 * ```
 *
 * ⚠ [RealmSetting] / [DeviceSetting] are **not** parameterised. The proposal's
 * `Setting<V>` / `RealmSetting<TBoolean>` shape (§3.1, §4.1–§4.3, §5) was
 * abandoned during implementation — the DB `dbEntryMapper` cannot resolve a
 * `typeVar`-typed value field — so the stored `value` is a self-describing
 * `genericData`. See [Setting]'s KDoc and the doc's own header banner.
 *
 * Writes ride [WriteSetting]; the typed `Preference<T>` ergonomics in
 * `:preference-service-client` wrap the raw chain with [PreferenceAdapter]
 * round-trips. See `docs/proposals/core-preference-service.md` §6.
 */
interface PreferenceService : Service {

    override val schema: ServiceSchema get() = Schema

    val realmSettings: TList<RealmSetting>

    val deviceSettings: TList<DeviceSetting>

    companion object {
        const val ID = "tenshu/preference/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:preference-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.REALM_SETTINGS,
                    type = TList.typeOf(RealmSetting.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_SETTINGS,
                    type = TList.typeOf(DeviceSetting.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val REALM_SETTINGS = "$ID/realmSettings"
        const val DEVICE_SETTINGS = "$ID/deviceSettings"
    }
}
