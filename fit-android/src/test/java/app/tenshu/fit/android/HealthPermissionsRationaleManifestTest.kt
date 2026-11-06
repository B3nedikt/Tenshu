package app.tenshu.fit.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Health Connect's two ways into Fit's permission rationale both land on
 * [HealthPermissionsRationaleActivity], and the rationale's "Fit only reads" stays true.
 *
 * ⚠ Neither entry point is reachable from inside the app, so nothing but this notices a wrong
 * target: both pointed at `MainActivity` until the rationale screen existed, and the link on Health
 * Connect's consent sheet opened the app (and its launch-time consent prompt) instead.
 */
class HealthPermissionsRationaleManifestTest {

    private val manifest: Element by lazy {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("fit-android/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile } ?: error("AndroidManifest.xml not found")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID, name)

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.values(tag: String): Set<String> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).androidAttr("name") }.toSet() }

    @Test
    fun `the Android 13 rationale intent opens the rationale activity, exported`() {
        val handlers = elements("activity").filter { SHOW_RATIONALE in it.values("action") }
        assertEquals(listOf(".HealthPermissionsRationaleActivity"), handlers.map { it.androidAttr("name") })
        assertEquals("true", handlers.single().androidAttr("exported"))
    }

    @Test
    fun `the Android 14 permission-usage alias targets the rationale activity`() {
        val alias = elements("activity-alias").single { it.androidAttr("name") == "ViewPermissionUsageActivity" }
        assertEquals(".HealthPermissionsRationaleActivity", alias.androidAttr("targetActivity"))
        assertEquals("true", alias.androidAttr("exported"))
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.androidAttr("permission"))
        assertTrue("android.intent.action.VIEW_PERMISSION_USAGE" in alias.values("action"))
        assertTrue("android.intent.category.HEALTH_PERMISSIONS" in alias.values("category"))
    }

    /** The rationale says Fit never writes to Health Connect; a WRITE permission here would make it a lie. */
    @Test
    fun `the release manifest declares no Health Connect write permission`() {
        val writes = elements("uses-permission").map { it.androidAttr("name") }
            .filter { it.startsWith("android.permission.health.WRITE_") }
        assertEquals(emptyList(), writes, "Update fit_hc_rationale_read_only before declaring any.")
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val SHOW_RATIONALE = "androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE"
    }
}
