package app.tenshu.calendar.data.external

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.Foundation.NSUserDefaults

/** The iOS digest store (CX-2b): device-local, per mirror, and absent until written. */
class UserDefaultsMirrorDigestStoreTest {

    // ⚠ A fresh suite per run: defaults persist out of process (cfprefsd), so a fixed suite can
    // still hold the last run's values even after `removePersistentDomainForName`.
    private val suite = "tenshu.test.mirror-digests.${Random.nextLong().toULong()}"
    private val defaults = NSUserDefaults(suiteName = suite)

    @AfterTest
    fun clear() {
        listOf("m1", "m2").forEach { defaults.removeObjectForKey("calendar.mirror.digest.$it") }
        defaults.removePersistentDomainForName(suite)
    }

    @Test
    fun a_digest_round_trips_per_mirror_and_is_absent_until_written() {
        val store = UserDefaultsMirrorDigestStore(defaults)

        assertNull(store.get("m1"))
        store.put("m1", "abc")
        store.put("m2", "def")
        assertEquals("abc", store.get("m1"))
        assertEquals("def", UserDefaultsMirrorDigestStore(defaults).get("m2"), "a second store reads it back")
    }
}
