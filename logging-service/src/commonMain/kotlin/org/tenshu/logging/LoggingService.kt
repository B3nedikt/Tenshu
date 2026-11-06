package org.tenshu.logging

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Phase 5 of `docs/proposals/core-error-channel.md`: proxy-side capture of
 * typed errors that escape impl bodies. Exposes one realm-scoped relation
 * [events] of [ErrorEvent]s, newest-first.
 *
 * The recorder lives in `:logging-service-proxy` and is wired into
 * `LocalTaskExecutor`'s single catch-all (proposal §4.2). Events are
 * `Syncable` so a user reviewing diagnostics on their desktop sees the same
 * history as on their phone, but realm-local — they never leave the device
 * cluster until Phase 6 lands the App-Console-gated upload CTA.
 *
 * Retention bounds are realm-local too (proposal §4.4):
 *  - **Ring buffer:** at most 500 events; oldest tombstoned on insert when
 *    over cap. Generous enough for a debugging session, small enough that
 *    an exception loop can't blow the realm.
 *  - **Time eviction:** events older than 30 days tombstoned by the daily
 *    sweep. User-facing rationale: "we only keep recent diagnostic events".
 *
 * **PII by construction (§5).** [ErrorEvent] carries typed `errorTypeId` +
 * non-PII platform metadata. The only freetext field is
 * [ErrorEvent.internalDetail], scoped to the `tenshu/core/error/internal`
 * code only; it is **proxy-local readable** and redacted at the upload seam
 * (Phase 6). The Settings → Errors screen renders it locally; the future
 * Console payload strips it.
 */
interface LoggingService : Service {

    override val schema: ServiceSchema get() = Schema

    val events: TList<ErrorEvent>

    companion object {
        const val ID = "tenshu/logging/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:logging-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.EVENTS,
                    type = TList.typeOf(ErrorEvent.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val EVENTS = "$ID/events"
    }
}
