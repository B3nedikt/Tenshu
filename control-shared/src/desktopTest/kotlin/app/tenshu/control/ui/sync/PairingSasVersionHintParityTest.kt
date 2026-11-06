package app.tenshu.control.ui.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The join-flow code-mismatch line is the same sentence on every surface that shows the join SAS.
 *
 * ⚠⚠ Why it matters: SAS v2 is lockstep (threat model T-P-14) — a device on an older Tenshu shows a
 * different code by construction, so "update both devices" is now part of what a mismatch means,
 * and a user comparing a phone with a laptop reads the two screens side by side. The four
 * catalogues cannot see each other (Compose resources, a Swift file, two i18next JSONs), so like
 * `ErrorCopyParityTest` this reads their sources.
 */
class PairingSasVersionHintParityTest {

    private val repoRoot = File("..").canonicalFile

    private fun read(path: String): String {
        val file = File(repoRoot, path)
        assertTrue(file.isFile, "Not found: $file — update this test if it moved")
        return file.readText()
    }

    @Test
    fun theVersionLineIsIdenticalOnComposeIosAndBothWebScreens() {
        val compose = Regex("""<string name="pairing_sas_version_hint">([^<]+)</string>""")
            .find(read("control-shared/src/commonMain/composeResources/values/strings.xml"))
            ?.groupValues?.get(1)
        val ios = Regex("""static let sasVersionHint = "([^"]+)"""")
            .find(read("control-ios/Sources/PairingView.swift"))
            ?.groupValues?.get(1)
        val webJoiner = Regex(""""versionHint": "([^"]+)"""")
            .find(read("web/apex/locales/en/onboarding.json"))
            ?.groupValues?.get(1)
        val webHost = Regex(""""versionHint": "([^"]+)"""")
            .find(read("web/apex/locales/en/control.json"))
            ?.groupValues?.get(1)

        val expected = "If the codes don't match, don't continue — make sure both devices are on " +
            "the latest version of Tenshu."
        assertEquals(expected, compose, "Compose (Android + desktop)")
        assertEquals(expected, ios, "iOS PairingStrings")
        assertEquals(expected, webJoiner, "web joiner (onboarding.json pairing.await)")
        assertEquals(expected, webHost, "web host (control.json devices.pair.request)")
    }
}
