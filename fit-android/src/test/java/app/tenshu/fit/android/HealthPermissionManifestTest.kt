package app.tenshu.fit.android

import app.tenshu.fit.data.HealthConnectImporter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The manifest must declare every Health Connect permission the importer *requires*.
 *
 * This is not a style check. An undeclared runtime permission can never be granted — the consent
 * sheet silently omits it — while `HealthConnectImporter.import` aborts on its missing-set check
 * before reading a single record. The user-visible result is a permanent "Fit needs permission to
 * read your health data" banner with an empty app, and a "Try again" that can never succeed.
 *
 * Nothing else catches a permission added to the importer without a manifest line — the Kotlin
 * constant and the XML are unrelated to the compiler, the app does not crash, and so the smoke gate
 * stays green.
 */
class HealthPermissionManifestTest {

    private val manifestText: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("fit-android/src/main/AndroidManifest.xml"),
        )
        val manifest = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found; looked in ${candidates.map { it.absolutePath }}")
        manifest.readText()
    }

    private val declaredPermissions: Set<String> by lazy {
        DECLARATION.findAll(manifestText).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every required health permission is declared in the manifest`() {
        val undeclared = HealthConnectImporter.PERMISSIONS - declaredPermissions
        assertEquals(
            emptySet(), undeclared,
            "HealthConnectImporter requires these permissions but the manifest does not declare them, " +
                "so they can never be granted and EVERY import will abort: $undeclared",
        )
    }

    /**
     * The optional grants ride the same consent sheet ([HealthConnectImporter.REQUESTED_PERMISSIONS]).
     * Undeclared they are merely dropped rather than fatal — routes stop reaching the map, deep
     * history silently clamps to 30 days — so pin them too.
     */
    @Test
    fun `every requested health permission is declared in the manifest`() {
        val undeclared = HealthConnectImporter.REQUESTED_PERMISSIONS - declaredPermissions
        assertEquals(
            emptySet(), undeclared,
            "These permissions are asked for at runtime but not declared, so the consent sheet " +
                "silently omits them: $undeclared",
        )
    }

    @Test
    fun `the deep-history grant is declared`() {
        assertTrue(
            HealthConnectImporter.HISTORY_PERMISSION in declaredPermissions,
            "Without it 'Import your full history' silently reads the same 30 days.",
        )
    }

    private companion object {
        val DECLARATION = Regex("""<uses-permission\s+android:name="(android\.permission\.health\.[A-Z_]+)"""")
    }
}
