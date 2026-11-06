package org.tenshu.mia

import org.tenshu.core.Service
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service

/**
 * Mia's query layer (`docs/proposals/mia-query-layer.md` MQ-D2): the one place an
 * utterance becomes an answer grounded in the user's own realm data.
 *
 * **Why the proxy.** The planner must read across *every* service. App-side that would
 * mean assembling each app's client stack inside Mia plus an IPC fan-out per query;
 * proxy-side it is an ordinary in-process read against services that are already
 * registered there. Mia's own chat and Home's embedded "Ask Mia" (the same `ChatScreen` via
 * `Destination.MiaChat`) converge here, and a hub speaker's voice turn is planned to — so the
 * planner is authored once, for the same reason the conversation surface is shared.
 *
 * ⚠ **The service holds no relations, and that is deliberate.** Mia stores nothing:
 * conversation history is device-local (MQ-D8) and every answer is derived on demand
 * from rows other services own. A `Syncable` transcript row would be the single most
 * sensitive artifact on the wire — what someone *asks* is routinely more revealing than
 * what they are shown — so it does not become one by accident here.
 *
 * The only operation is [AskMia]. It is a **read**: MQ-D10's validator enforces that
 * (the same callstack shape carries every write in the platform, so read-only is
 * something we check, never something the DSL grants). A mutating plan needs MQ-D7's
 * confirmation cell.
 */
interface MiaService : Service {

    override val schema: ServiceSchema get() = Schema

    companion object {
        const val ID = "tenshu/mia/service"

        /**
         * ⚠ Even an *optional* field added to [MiaAnswer]/[MiaHit] is a positional reshape —
         * composites carry no field tags, so an old reader mis-parses everything after the
         * insertion point rather than ignoring it. HR #9.
         */
        // v1 is the release baseline (`core-schema-evolution.md` Appendix A).
        val Schema: ServiceSchema = service(id = ID, version = 1)
    }
}
