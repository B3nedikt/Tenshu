package app.tenshu.home.data

import app.tenshu.home.model.Automation
import kotlinx.coroutines.flow.Flow

/**
 * The Automations surface (docs/proposals/home-and-mia-apps.md §5, M2). M2 binds
 * [MockAutomationsRepository]; the real proxy-side routines engine replaces it behind this
 * interface — same seam as [HomeDevicesRepository].
 */
interface AutomationsRepository {

    /** All automations, stable order (creation order in the mock). */
    fun automations(): Flow<List<Automation>>

    /** Persists a new automation ([Automation.id] is assigned here) and returns its id. */
    suspend fun addAutomation(automation: Automation): String

    /** Replaces the automation with [Automation.id]; unknown ids are ignored. */
    suspend fun updateAutomation(automation: Automation)

    suspend fun removeAutomation(automationId: String)

    suspend fun setEnabled(automationId: String, enabled: Boolean)
}
