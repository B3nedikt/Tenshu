@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.outbox

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** iOS twin of `FileOutboxStoreTest` — the R-iOS-3 durable recorder journal on the sim gate. */
class NsFileOutboxStoreTest {

    // A fresh container-adjacent dir per test method (kotlin.test builds one instance per @Test).
    private val dir = NSTemporaryDirectory() + "outbox-" + NSProcessInfo.processInfo.globallyUniqueString

    @AfterTest
    fun cleanup() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    @Test
    fun appendEntriesRemoveRoundTrip() {
        val store = NsFileOutboxStore(dir)
        store.append("100-a", """{"v":1}""")
        store.append("200-b", """{"v":2}""")
        assertEquals(
            listOf(OutboxEntry("100-a", """{"v":1}"""), OutboxEntry("200-b", """{"v":2}""")),
            store.entries(),
        )
        store.remove("100-a")
        assertEquals(listOf(OutboxEntry("200-b", """{"v":2}""")), store.entries())
    }

    @Test
    fun idsAreSanitisedConsistently() {
        val store = NsFileOutboxStore(dir)
        store.append("1700000000-w/rec:1", "payload")
        assertEquals(listOf("1700000000-w_rec_1"), store.entries().map { it.id })
        // remove derives the same sanitised filename, so it finds what append wrote.
        store.remove("1700000000-w/rec:1")
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun foreignFilesAreIgnored() {
        val store = NsFileOutboxStore(dir)
        store.append("100-a", "ok")
        ("not an entry" as NSString).writeToFile("$dir/readme.txt", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        assertEquals(listOf("100-a"), store.entries().map { it.id })
    }

    @Test
    fun missingDirReadsEmpty() {
        assertTrue(NsFileOutboxStore("$dir/does-not-exist").entries().isEmpty())
    }
}
