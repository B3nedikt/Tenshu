package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Trigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MockAutomationsRepositoryTest {

    private fun draft(name: String) = Automation(
        id = "",
        name = name,
        enabled = true,
        trigger = Trigger.Schedule(minutesOfDay = 8 * 60),
        conditions = emptyList(),
        actions = listOf(Action.Notify(message = "Good morning")),
        lastRunAt = null,
    )

    @Test
    fun seedSpansEnabledAndDisabledAutomations() = runTest {
        val automations = MockAutomationsRepository().automations().first()
        assertTrue(automations.any { it.enabled })
        assertTrue(automations.any { !it.enabled })
    }

    @Test
    fun addAssignsAnIdAndAppends() = runTest {
        val repository = MockAutomationsRepository()
        val id = repository.addAutomation(draft("Morning"))

        val automations = repository.automations().first()
        assertEquals(id, automations.last().id)
        assertTrue(id.isNotBlank())
        assertEquals("Morning", automations.last().name)
    }

    @Test
    fun updateReplacesByIdAndKeepsOrder() = runTest {
        val repository = MockAutomationsRepository()
        val before = repository.automations().first()
        val target = before[1]

        repository.updateAutomation(target.copy(name = "Renamed"))

        val after = repository.automations().first()
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals("Renamed", after[1].name)
    }

    @Test
    fun setEnabledFlipsOnlyTheTarget() = runTest {
        val repository = MockAutomationsRepository()
        val target = repository.automations().first().first { it.enabled }

        repository.setEnabled(target.id, false)

        val after = repository.automations().first()
        assertFalse(after.first { it.id == target.id }.enabled)
        assertEquals(
            repository.automations().first().size,
            after.size,
        )
    }

    @Test
    fun removeDropsTheAutomation() = runTest {
        val repository = MockAutomationsRepository()
        val target = repository.automations().first().first()
        repository.removeAutomation(target.id)
        assertTrue(repository.automations().first().none { it.id == target.id })
    }
}
