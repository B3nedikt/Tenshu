package app.tenshu.outbox

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Payload(val id: String, val value: Int)

private class InMemoryStore : OutboxStore {
    val backing = LinkedHashMap<String, String>()
    override fun append(id: String, payload: String) { backing[id] = payload }
    override fun entries(): List<OutboxEntry> = backing.map { OutboxEntry(it.key, it.value) }
    override fun remove(id: String) { backing.remove(id) }
}

class JsonOutboxTest {

    private fun outbox(store: OutboxStore) =
        JsonOutbox(store, Payload.serializer(), idOf = { "${it.value}-${it.id}" })

    @Test
    fun persistedEntriesRoundTripOldestFirst() {
        val store = InMemoryStore()
        val outbox = outbox(store)
        outbox.persist(Payload("a", 1))
        outbox.persist(Payload("b", 2))
        assertEquals(listOf("1-a" to Payload("a", 1), "2-b" to Payload("b", 2)), outbox.pending())
    }

    @Test
    fun removeConfirmsAWrite() {
        val store = InMemoryStore()
        val outbox = outbox(store)
        val id = outbox.persist(Payload("a", 1))
        outbox.remove(id)
        assertTrue(outbox.pending().isEmpty())
        assertTrue(store.backing.isEmpty())
    }

    @Test
    fun duplicatePersistFoldsToOneEntry() {
        val store = InMemoryStore()
        val outbox = outbox(store)
        outbox.persist(Payload("a", 1))
        outbox.persist(Payload("a", 1))
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun corruptPayloadIsDroppedAndPurged() {
        val store = InMemoryStore()
        val outbox = outbox(store)
        outbox.persist(Payload("a", 1))
        store.backing["0-corrupt"] = "{not json"
        assertEquals(listOf("1-a" to Payload("a", 1)), outbox.pending())
        assertTrue("0-corrupt" !in store.backing)
    }
}
