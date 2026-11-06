package app.tenshu.outbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileOutboxStoreTest {

    private fun tempStore(): Pair<FileOutboxStore, File> {
        val dir = File.createTempFile("outbox", null).let { f -> f.delete(); f }
        return FileOutboxStore(dir) to dir
    }

    @Test
    fun appendEntriesRemoveRoundTrip() {
        val (store, dir) = tempStore()
        store.append("100-a", """{"v":1}""")
        store.append("200-b", """{"v":2}""")
        assertEquals(
            listOf(OutboxEntry("100-a", """{"v":1}"""), OutboxEntry("200-b", """{"v":2}""")),
            store.entries(),
        )
        store.remove("100-a")
        assertEquals(listOf(OutboxEntry("200-b", """{"v":2}""")), store.entries())
        dir.deleteRecursively()
    }

    @Test
    fun idsAreSanitisedConsistently() {
        val (store, dir) = tempStore()
        store.append("1700000000-w/rec:1", "payload")
        assertEquals(listOf("1700000000-w_rec_1"), store.entries().map { it.id })
        store.remove("1700000000-w/rec:1")
        assertTrue(store.entries().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun tmpFilesAndForeignFilesAreIgnored() {
        val (store, dir) = tempStore()
        store.append("100-a", "ok")
        File(dir, "150-b.json.tmp").writeText("half")
        File(dir, "readme.txt").writeText("not an entry")
        assertEquals(listOf("100-a"), store.entries().map { it.id })
        dir.deleteRecursively()
    }
}
