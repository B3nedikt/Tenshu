package org.tenshu.core.common.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The first-party signer rule (threat model T-C-20) and the table it pins. */
class FirstPartySigningTest {

    @Test
    fun aFirstPartyPackageWithItsPlayKeyPasses() {
        var asked: ByteArray? = null
        val passes = FirstPartySigning.isFirstParty(
            packageName = DRIVE,
            hasPinnedCertificate = { asked = it; true },
            sharesSignerWithSelf = { false },
        )
        assertTrue(passes)
        assertContentEquals(FirstPartySigning.digestBytes(DRIVE_DIGEST), asked, "asked about Drive's own key")
        assertEquals(32, asked!!.size)
    }

    /** Debug builds and the side-channel key: signed like the app asking. */
    @Test
    fun aFirstPartyPackageSignedLikeTheAskingAppPasses() {
        assertTrue(FirstPartySigning.isFirstParty(DRIVE, hasPinnedCertificate = { false }, sharesSignerWithSelf = { true }))
    }

    /**
     * ⚠⚠ Mixing channels: Play Control with a downloaded Drive, or the reverse. Neither shares a
     * signer with the other, so only the pinned side-channel digest admits it.
     */
    @Test
    fun aFirstPartyPackageWithTheSideChannelKeyPasses() {
        val asked = mutableListOf<String>()
        val passes = FirstPartySigning.isFirstParty(
            packageName = DRIVE,
            hasPinnedCertificate = { asked += it.toHex(); it.toHex() == SIDE_CHANNEL.toHex() },
            sharesSignerWithSelf = { false },
            sideChannelDigest = SIDE_CHANNEL_DIGEST,
        )
        assertTrue(passes)
        assertEquals(
            listOf(FirstPartySigning.digestBytes(DRIVE_DIGEST).toHex(), SIDE_CHANNEL.toHex()),
            asked,
            "Drive's Play key first, then the side channel's",
        )
    }

    /** Control's direction: the same rule, the same digest. */
    @Test
    fun controlSignedWithTheSideChannelKeyPasses() {
        assertTrue(
            FirstPartySigning.isFirstParty(
                FirstPartySigning.CONTROL_PACKAGE,
                hasPinnedCertificate = { it.toHex() == SIDE_CHANNEL.toHex() },
                sharesSignerWithSelf = { false },
                sideChannelDigest = SIDE_CHANNEL_DIGEST,
            ),
        )
    }

    /** ⚠ The side-channel key admits the ten names only, like every other rule here. */
    @Test
    fun theSideChannelKeyAdmitsNoPackageOutsideTheSet() {
        assertFalse(
            FirstPartySigning.isFirstParty(
                "com.example.flashlight",
                hasPinnedCertificate = { true },
                sharesSignerWithSelf = { false },
                sideChannelDigest = SIDE_CHANNEL_DIGEST,
            ),
        )
    }

    /** ⚠⚠ With no side-channel key pinned, nothing beyond the Play key is asked about. */
    @Test
    fun anUnsetSideChannelSlotAsksAboutThePlayKeyOnly() {
        val asked = mutableListOf<String>()
        val passes = FirstPartySigning.isFirstParty(
            DRIVE,
            hasPinnedCertificate = { asked += it.toHex(); false },
            sharesSignerWithSelf = { false },
            sideChannelDigest = null,
        )
        assertFalse(passes)
        assertEquals(listOf(FirstPartySigning.digestBytes(DRIVE_DIGEST).toHex()), asked)
    }

    @Test
    fun aFirstPartyNameWithAnyOtherSignerFails() {
        assertFalse(FirstPartySigning.isFirstParty(DRIVE, hasPinnedCertificate = { false }, sharesSignerWithSelf = { false }))
    }

    @Test
    fun aPackageOutsideTheSetFailsEvenWhenSignedLikeTheAskingApp() {
        assertFalse(
            FirstPartySigning.isFirstParty(
                "com.example.flashlight",
                hasPinnedCertificate = { true },
                sharesSignerWithSelf = { true },
            ),
        )
    }

    @Test
    fun theTableHoldsTheTenAppsWithWellFormedDigests() {
        assertEquals(10, FirstPartySigning.PACKAGES.size)
        FirstPartySigning.PLAY_SIGNING_CERT_SHA256.forEach { (pkg, digest) ->
            assertTrue(pkg.startsWith("app.tenshu.") && pkg.endsWith(".android"), pkg)
            assertEquals(32, FirstPartySigning.digestBytes(digest).size, pkg)
        }
        assertEquals(10, FirstPartySigning.PLAY_SIGNING_CERT_SHA256.values.toSet().size, "one key per app")
    }

    /**
     * ⚠ The release doc's table is where a person reads, and updates, a key. The pinned table must
     * say the same, or a Play key upgrade recorded there locks the apps out of Control on release.
     */
    @Test
    fun theTableMatchesTheReleaseDoc() {
        val doc = releaseDoc()
        val row = Regex("""^\| (\w+) \| `([0-9A-F:]{95})` \|$""", RegexOption.MULTILINE)
        val fromDoc = row.findAll(doc).associate { m ->
            "app.tenshu.${m.groupValues[1].lowercase()}.android" to m.groupValues[2]
        }
        assertEquals(FirstPartySigning.PLAY_SIGNING_CERT_SHA256, fromDoc)
    }

    /**
     * The side-channel digest is pinned in code and written in the release doc, and the two must
     * agree, including on "not created yet". A pinned digest must be well formed and must not be
     * one of the Play keys: the side channel is a separate identity by decision.
     */
    @Test
    fun theSideChannelSlotMatchesTheReleaseDoc() {
        val line = Regex(
            """^\*\*Side-channel key, SHA-256 \(all ten apps\):\*\* (none yet|`([0-9A-F:]{95})`)$""",
            RegexOption.MULTILINE,
        )
        val match = assertNotNull(line.find(releaseDoc()), "the release doc states the side-channel digest, or 'none yet'")
        assertEquals(FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256, match.groupValues[2].ifEmpty { null })
        FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256?.let { pinned ->
            assertEquals(32, FirstPartySigning.digestBytes(pinned).size)
            assertFalse(pinned in FirstPartySigning.PLAY_SIGNING_CERT_SHA256.values, "not a Play key")
        }
    }

    private fun releaseDoc(): String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/how-to/android-release.md") }
        .first { it.exists() }
        .readText()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val DRIVE = "app.tenshu.drive.android"
        const val DRIVE_DIGEST =
            "3D:9E:B2:4D:B3:51:25:A3:73:08:60:63:4A:75:4A:CA:83:AE:F0:3D:D2:76:89:EB:80:40:A0:D9:13:70:F1:39"

        /** Made up for the tests, deliberately not the real side-channel key. */
        val SIDE_CHANNEL: ByteArray = ByteArray(32) { (0xA0 + it).toByte() }
        val SIDE_CHANNEL_DIGEST: String = SIDE_CHANNEL.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
}
