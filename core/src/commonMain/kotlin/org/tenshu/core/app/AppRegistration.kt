package org.tenshu.core.app

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Durable, synced *"this app is installed here"* record
 * (`docs/proposals/control-app-registration.md` §4). One row per
 * `(appId, deviceId)` — installation is inherently a per-device fact (Drive
 * may be on the laptop and not the phone). Written by the **proxy** on an app's
 * first verified connection (never by the connecting app); its [createdAt] is
 * the Apps-tab *install date* (`control-ia.md` §4.2).
 *
 * Reuses the [DeviceSetting][org.tenshu.preference.DeviceSetting]-style
 * `compositeKey = "$deviceId/$appId"` trick so the platform's single-field
 * `stringDataId(...)` `DataIdProvider` keys it with no new sync machinery. Every
 * field is a concrete scalar, so the `collectFields` / typeVar-dbEntryMapper
 * caveats that the preference service hit do not apply here.
 *
 * Removal sets [state] = [State.REMOVED] rather than deleting the row: the
 * tombstone is what suppresses silent re-registration by a still-installed
 * binary (§5.1). The two are joined to [AppGrant] by [appId] at the UI layer;
 * `AppRegistration` itself carries no permission state.
 */
interface AppRegistration : Composite {

    override val schema: CompositeSchema get() = Schema

    val compositeKey: TString
        get() = getField(Field.COMPOSITE_KEY)

    val appId: TString
        get() = getField(Field.APP_ID)

    val deviceId: TString
        get() = getField(Field.DEVICE_ID)

    val createdAt: UnixInstant
        get() = getField(Field.CREATED_AT)

    val state: TString
        get() = getField(Field.STATE)

    val verified: TBoolean
        get() = getField(Field.VERIFIED)

    companion object {
        const val ID = "tenshu/app/registration"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                AppRegistrationDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.COMPOSITE_KEY)
                string(Field.APP_ID)
                string(Field.DEVICE_ID)
                timestamp(Field.CREATED_AT)
                string(Field.STATE)
                boolean(Field.VERIFIED)
            },
        )

        fun create(
            context: TContext,
            appId: String,
            deviceId: String,
            createdAt: Long,
            state: String,
            verified: Boolean,
        ): AppRegistration = context.create(
            type = ID,
            fields = listOf(
                compositeKey(appId, deviceId),
                appId,
                deviceId,
                createdAt,
                state,
                verified,
            ),
        )

        /**
         * Build the derived `compositeKey` from its components. Exposed so the
         * proxy can derive the same `StringDataId` when looking a row up for
         * the idempotency check without first materialising it. Mirrors
         * `DeviceSetting.compositeKey`.
         */
        fun compositeKey(appId: String, deviceId: String): String =
            "$deviceId/$appId"
    }

    /** Registration lifecycle state. Stored as a [TString]; not deleted on removal. */
    object State {
        const val ACTIVE = "Active"
        const val REMOVED = "Removed"
    }

    object Field {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val APP_ID = "$ID/appId"
        const val DEVICE_ID = "$ID/deviceId"
        const val CREATED_AT = "$ID/createdAt"
        const val STATE = "$ID/state"
        const val VERIFIED = "$ID/verified"
    }
}

val Entity<out AppRegistration>.appId: TString
    get() = getFieldValue(AppRegistration.Field.APP_ID)

interface AppRegistrationDataIdProvider : CompositeDataIdProvider<AppRegistration> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/app/registration/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = AppRegistration.Field.COMPOSITE_KEY,
        )
    }
}
