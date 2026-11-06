package org.tenshu.core.app

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service surface for app registration + permission state
 * (`docs/proposals/control-app-registration.md`). Exposes two relations that
 * Control's Apps section traverses with the standard chain DSL:
 *
 *  - [appRegistrations] — every locally-known [AppRegistration] (one per
 *    `(appId, deviceId)`); the Apps list groups these by `appId`.
 *  - [appGrants] — every [AppGrant] (one per `(appId, serviceId, operationId)`);
 *    the permission widget reads grant state from these.
 *
 * Writes ride [RegisterApp] (proxy-only, at handshake time), [RemoveApp] and
 * [SetAppGrant] (user-initiated from Control). The relations are derived views
 * maintained by the proxy-side relation maintainer (`initAppService`), exactly
 * like `PreferenceService.realmSettings`.
 *
 * Lives in `:core` rather than a `:rei-service` module by the same reasoning as
 * `tenshu-rei.md` Q9: registration ships value before the Rei ledger is
 * operational and should not inherit its not-started status. If `:rei-service`
 * later materialises, this moves there (`control-app-registration.md` §7).
 */
interface AppService : Service {

    override val schema: ServiceSchema get() = Schema

    val appRegistrations: TList<AppRegistration>

    val appGrants: TList<AppGrant>

    companion object {
        const val ID = "tenshu/app/service"

        // `maintained = true` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:core-common` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.APP_REGISTRATIONS,
                    type = TList.typeOf(AppRegistration.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.APP_GRANTS,
                    type = TList.typeOf(AppGrant.Schema),
                    maintained = true,
                ),
            ),
            // v1 = release baseline (`core-schema-evolution.md` Appendix A).
            // ⚠ A wire-only reshape (e.g. an op return composite like `OpenOutcome`) is still a
            // version event (HR #9); its chain step goes in `CoreCommonModule`.
            version = 1,
        )
    }

    object Relations {
        const val APP_REGISTRATIONS = "$ID/appRegistrations"
        const val APP_GRANTS = "$ID/appGrants"
    }
}
