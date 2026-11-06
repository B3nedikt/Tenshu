package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W-1 (`drive-synced-thumbnails.md` §15.2 D-5): the declaration row, its writer on the pull path,
 * and `wanted(realm)` as the union over every device's row.
 */
class WantedThumbnailClassesTest {

    @Test
    fun aPullOfADeclarableRungWritesTheDeviceRow_once() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1")
        val wanted = WantedThumbnailClasses { "device-1" }

        wanted.notePull(proxy.executionContext, File.ThumbnailXLarge.id)
        wanted.notePull(proxy.executionContext, File.ThumbnailXLarge.id)
        wanted.notePull(proxy.executionContext, File.ThumbnailMotion.id)

        assertEquals(
            setOf(GetStoredThumbnail.CLASS_XLARGE, GetStoredThumbnail.CLASS_MOTION),
            proxy.executionContext.readWantedThumbnailClasses("device-1"),
        )
    }

    /** `LIST` and `LARGE` are wanted everywhere implicitly; a pull of them declares nothing. */
    @Test
    fun theImplicitClassesAndNonThumbnailsAreNeverDeclared() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1")
        val wanted = WantedThumbnailClasses { "device-1" }

        wanted.notePull(proxy.executionContext, File.Thumbnail.id)
        wanted.notePull(proxy.executionContext, File.ThumbnailLarge.id)
        wanted.notePull(proxy.executionContext, File.Data.id)
        wanted.declare(proxy.executionContext, "xxlarge-that-does-not-exist")

        assertTrue(proxy.executionContext.readWantedThumbnailClasses("device-1").isEmpty())
        assertNull(proxy.executionContext.readDeviceSettingData("device-1", ThumbnailPreferences.WantedClasses.id), "no row at all")
    }

    /** The row is a union: a class declared earlier (by an app, or a previous process) survives a later pull. */
    @Test
    fun aLaterPullUnionsWithWhatTheRowAlreadySays() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1")
        proxy.executionContext.writeWantedThumbnailClasses("device-1", setOf(GetStoredThumbnail.CLASS_MOTION))

        WantedThumbnailClasses { "device-1" }.notePull(proxy.executionContext, File.ThumbnailXLarge.id)

        assertEquals(
            setOf(GetStoredThumbnail.CLASS_MOTION, GetStoredThumbnail.CLASS_XLARGE),
            proxy.executionContext.readWantedThumbnailClasses("device-1"),
        )
    }

    /** No identity yet: nothing is written, and the next pull tries again. */
    @Test
    fun withoutADeviceIdNothingIsWritten() = runTest {
        val proxy = createFileProxy(backgroundScope)
        var id: String? = null
        val wanted = WantedThumbnailClasses { id }

        wanted.notePull(proxy.executionContext, File.ThumbnailXLarge.id)
        assertTrue(proxy.executionContext.readWantedThumbnailClasses("device-1").isEmpty())

        id = "device-1"
        wanted.notePull(proxy.executionContext, File.ThumbnailXLarge.id)
        assertEquals(setOf(GetStoredThumbnail.CLASS_XLARGE), proxy.executionContext.readWantedThumbnailClasses("device-1"))
    }

    // ---- wanted(realm) -------------------------------------------------------------------------

    @Test
    fun wantedRealmIsTheUnionOverEveryDeviceRow() = runTest {
        val store = InMemoryObjectStore()
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1", objectStore = store)
        proxy.executionContext.writeWantedThumbnailClasses("phone", setOf(GetStoredThumbnail.CLASS_MOTION))
        proxy.executionContext.writeWantedThumbnailClasses("desktop", setOf(GetStoredThumbnail.CLASS_XLARGE))
        // A row that (somehow) names an implicit class or an unknown one contributes nothing.
        proxy.executionContext.writeDeviceSettingValue(
            deviceId = "watch",
            preferenceId = ThumbnailPreferences.WantedClasses.id,
            value = org.tenshu.preference.StringListAdapter.toData(listOf("list", "nonsense"), proxy.tContext),
        )
        // Some other device setting must not be mistaken for a declaration.
        proxy.executionContext.writeDeviceSettingValue(
            deviceId = "phone",
            preferenceId = "some.other.preference",
            value = org.tenshu.preference.StringListAdapter.toData(listOf("xlarge"), proxy.tContext),
        )

        assertEquals(
            setOf(GetStoredThumbnail.CLASS_MOTION, GetStoredThumbnail.CLASS_XLARGE),
            proxy.executionContext.wantedThumbnailClassesInRealm(store),
        )
    }

    @Test
    fun aRealmWithNoRowsWantsNothing_andThatIsNotNull() = runTest {
        val store = InMemoryObjectStore()
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1", objectStore = store)

        assertEquals(emptySet(), proxy.executionContext.wantedThumbnailClassesInRealm(store))
    }
}
