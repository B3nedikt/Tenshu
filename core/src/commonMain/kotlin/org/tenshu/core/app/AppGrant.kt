package org.tenshu.core.app

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Realm-wide permission grant for one app capability
 * (`docs/proposals/tenshu-rei.md` §3.3, `control-app-registration.md` §2). One
 * row per `(appId, serviceId, operationId)` carrying the tri-state [grant].
 * *"Grant follows the app, capped by role"* (`tenshu-rei.md` Q29) — grants are
 * realm-level, independent of which device the app is installed on.
 *
 * Keyed via a derived `compositeKey = "$appId/$serviceId/$operationId"` so the
 * single-field `stringDataId(...)` provider applies, exactly like
 * [AppRegistration]. Purged when its app is removed (`control-app-registration.md`
 * §5).
 */
interface AppGrant : Composite {

    override val schema: CompositeSchema get() = Schema

    val compositeKey: TString
        get() = getField(Field.COMPOSITE_KEY)

    val appId: TString
        get() = getField(Field.APP_ID)

    val serviceId: TString
        get() = getField(Field.SERVICE_ID)

    val operationId: TString
        get() = getField(Field.OPERATION_ID)

    val grant: TString
        get() = getField(Field.GRANT)

    val lastUsedAt: UnixInstant
        get() = getField(Field.LAST_USED_AT)

    companion object {
        const val ID = "tenshu/app/grant"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                AppGrantDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.COMPOSITE_KEY)
                string(Field.APP_ID)
                string(Field.SERVICE_ID)
                string(Field.OPERATION_ID)
                string(Field.GRANT)
                timestamp(Field.LAST_USED_AT)
            },
        )

        fun create(
            context: TContext,
            appId: String,
            serviceId: String,
            operationId: String,
            grant: String,
            lastUsedAt: Long = 0L,
        ): AppGrant = context.create(
            type = ID,
            fields = listOf(
                compositeKey(appId, serviceId, operationId),
                appId,
                serviceId,
                operationId,
                grant,
                lastUsedAt,
            ),
        )

        fun compositeKey(appId: String, serviceId: String, operationId: String): String =
            "$appId/$serviceId/$operationId"
    }

    /** Tri-state grant. Stored as a [TString]. */
    object Grant {
        const val GRANTED = "Granted"
        const val DENIED = "Denied"
        const val ASK_EACH_TIME = "AskEachTime"
    }

    object Field {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val APP_ID = "$ID/appId"
        const val SERVICE_ID = "$ID/serviceId"
        const val OPERATION_ID = "$ID/operationId"
        const val GRANT = "$ID/grant"
        const val LAST_USED_AT = "$ID/lastUsedAt"
    }
}

val Entity<out AppGrant>.appId: TString
    get() = getFieldValue(AppGrant.Field.APP_ID)

interface AppGrantDataIdProvider : CompositeDataIdProvider<AppGrant> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/app/grant/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = AppGrant.Field.COMPOSITE_KEY,
        )
    }
}
