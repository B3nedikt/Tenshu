package org.tenshu.core.realmdata

import org.tenshu.core.Service
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service

/**
 * Receiver for the generic operations that act on **realm data as a category** —
 * the destructive verb Control's Data section was missing
 * (`docs/proposals/control-data-reset.md`). v1 carries exactly one:
 * [DeleteRealmData].
 *
 * ### Why its own service rather than [org.tenshu.core.app.AppService]
 *
 * Because §2 (D1) is the whole design: **the unit of reset is a data *type*, not an
 * app.** Apps are lenses over shared realm rows and that is literally true of the
 * rows in question — Timeline's importer writes the same `Workout`s Fit records — so
 * "clear Fit's data" would delete another app's work. Hanging the verb off
 * `AppService`, whose every other operation is keyed by `appId`, would encode exactly
 * the model the proposal rejects, in the one place a reader would look for it.
 *
 * ### No relations, deliberately
 *
 * The *read* surface for realm data already exists and is not shaped like a service
 * relation: [org.tenshu.core.core.SchemaProvider.realmDataTypeIds] enumerates the
 * types, and each type's own service exposes its rows. This service is a verb
 * receiver only — adding a relation here would mint a second, competing catalogue.
 *
 * ### Who may call it
 *
 * v1 is **Control-only** (§7). Nothing enforces that mechanically because nothing has
 * to: Control *is* the proxy, so the operation resolves in-process and is simply never
 * offered to an IPC client's UI. When per-row deletes arrive (§4 Option A) they become
 * ordinary app-callable operations and pick up the destructive capability class.
 */
interface RealmDataService : Service {

    override val schema: ServiceSchema get() = Schema

    companion object {
        const val ID = "tenshu/realmData/service"

        val Schema: ServiceSchema = service(id = ID)
    }
}
