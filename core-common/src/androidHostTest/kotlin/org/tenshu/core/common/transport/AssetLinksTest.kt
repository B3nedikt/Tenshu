package org.tenshu.core.common.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `web/apex/public/.well-known/assetlinks.json` is what Android and Play fetch to verify an App
 * Link (`android:autoVerify="true"`) on tenshu.app. It must name exactly the apps that declare one,
 * each with the keys its installs are signed with, or links open the browser instead of the app.
 */
class AssetLinksTest {

    @Test
    fun theFileNamesEveryAppThatVerifiesTenshuAppWithItsSigningKeys() {
        val expected = appsVerifyingTheApex().associateWith { pkg ->
            listOfNotNull(
                FirstPartySigning.PLAY_SIGNING_CERT_SHA256.getValue(pkg),
                FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256,
            ).toSet()
        }
        assertTrue(expected.isNotEmpty(), "no manifest declares an App Link on tenshu.app; check the scan")
        assertEquals(expected, assetLinks())
    }

    /** Package → fingerprints, as the published file states them. Must parse as JSON, as Google's verifier does. */
    private fun assetLinks(): Map<String, Set<String>> {
        val text = File(repoRoot(), "web/apex/public/.well-known/assetlinks.json").readText()
        val statements = Json.parseToJsonElement(text).jsonArray
        val links = statements.associate { statement ->
            val obj = statement.jsonObject
            assertEquals(
                listOf("delegate_permission/common.handle_all_urls"),
                obj.getValue("relation").jsonArray.map { it.jsonPrimitive.content },
            )
            val target = obj.getValue("target").jsonObject
            assertEquals("android_app", target.getValue("namespace").jsonPrimitive.content)
            target.getValue("package_name").jsonPrimitive.content to
                target.getValue("sha256_cert_fingerprints").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
        assertEquals(statements.size, links.size, "one statement per app")
        return links
    }

    /** First-party apps whose manifest has an auto-verified intent filter on the apex host. */
    private fun appsVerifyingTheApex(): Set<String> {
        val filter = Regex("""<intent-filter[^>]*android:autoVerify="true"[^>]*>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
        return FirstPartySigning.PACKAGES.filter { pkg ->
            val manifest = File(repoRoot(), "${moduleOf(pkg)}/src/main/AndroidManifest.xml")
            assertTrue(manifest.exists(), "manifest for $pkg at $manifest")
            filter.findAll(manifest.readText()).any { """android:host="$APEX_HOST"""" in it.groupValues[1] }
        }.toSet()
    }

    /** `app.tenshu.drive.android` → `drive-android`; Control predates the split and is `control`. */
    private fun moduleOf(pkg: String): String {
        val app = pkg.removePrefix("app.tenshu.").removeSuffix(".android")
        return if (pkg == FirstPartySigning.CONTROL_PACKAGE) "control" else "$app-android"
    }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "web/apex/public").isDirectory }

    private companion object {
        const val APEX_HOST = "tenshu.app"
    }
}
