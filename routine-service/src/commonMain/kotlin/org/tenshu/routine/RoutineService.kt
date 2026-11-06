package org.tenshu.routine

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The routines service (`routines-engine.md` RE-D11): automations as realm
 * data, evaluated and executed **entirely locally** by an engine that runs in
 * every proxy.
 *
 * ⭐ **The hub is not special-cased.** It is simply the device that usually wins
 * the RE-D2 executor claim, because it is always on. The one genuinely
 * hub-local piece is the device-event fast path, and even that is a seam
 * (`LocalDeviceEventSource`, RE-2) bound only in a process that hosts a
 * `DeviceBridge`.
 *
 * ⭐ **The zero-network property falls out with no new mechanism**: when the
 * executor is also the bridge host, the engine's `DeviceCommand` row is written
 * to the *local* store, the local observer fires immediately, and the bridge
 * acts. The relay is never in the path; the row syncs afterwards purely as the
 * audit trail.
 *
 * ⚖️ **Naming: `Routine` in code, "Automations" in the UI.** `milestones.md`
 * says routines; the shipped, localised Home UI says automations. Renaming
 * shipped translated strings for internal consistency is pure churn — the
 * mapping is one line in the repository.
 */
interface RoutineService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Every routine in the realm; authored by any device, held by all. */
    val routines: TList<Routine>

    /** Run history — also the cross-device dedupe receipts (RE-D3). */
    val routineRuns: TList<RoutineRun>

    /** Per-device presence (RE-4); a location-capable device authors its own row. */
    val presenceStates: TList<PresenceState>

    companion object {
        const val ID = "tenshu/routine/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:routine-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.ROUTINES,
                    type = TList.typeOf(Routine.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.ROUTINE_RUNS,
                    type = TList.typeOf(RoutineRun.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.PRESENCE_STATES,
                    type = TList.typeOf(PresenceState.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val ROUTINES = "$ID/routines"
        const val ROUTINE_RUNS = "$ID/routineRuns"

        /** RE-4: per-device presence, one row per reporting device. */
        const val PRESENCE_STATES = "$ID/presenceStates"
    }
}
