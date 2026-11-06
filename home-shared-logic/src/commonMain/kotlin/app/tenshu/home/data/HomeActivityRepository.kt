package app.tenshu.home.data

import app.tenshu.home.model.ActivityEvent
import kotlinx.coroutines.flow.Flow

/**
 * The Activity feed (docs/proposals/home-and-mia-apps.md §3, M2): read-only from the app's
 * perspective — events are produced by devices, the routines engine, and Mia. The mock
 * seeds a plausible recent history; the real source is a later phase.
 */
interface HomeActivityRepository {

    /** Events newest-first. */
    fun events(): Flow<List<ActivityEvent>>
}
