package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The admission decision with the two Security.framework calls faked: which requirement the
 * peer's code satisfies, and which team its signature names.
 *
 * Covers the Mac App Store / TestFlight route (`desktop-ipc-client-identity.md` §10). Apple
 * re-signs those copies with its own leaf certificate, so the team-OU requirement fails and the
 * decision must fall back to the store marker **plus** the signed team identifier. Each admit
 * case is paired with the reject case that differs in exactly one input. The real `SecCode`
 * path is covered by [MacPeerAttestationTest]; the last test here runs the actual requirement
 * strings through `codesign` against real Apple-signed bundles.
 */
class MacPeerAttestationDecisionTest {

    private val ours = "752LWAZMKZ"
    private val hint = "app.tenshu.control.ios"

    /** A peer satisfying exactly [satisfied] and signed for [signedTeam]; records what was asked. */
    private inner class FakePeer(
        private val satisfied: Set<String>,
        private val signedTeam: String?,
    ) {
        val asked = mutableListOf<String>()
        var teamReads = 0

        fun decide(teams: Set<String> = setOf(ours), claimed: String = hint) =
            MacPeerAttestation.decide(
                hint = claimed,
                teams = teams,
                satisfies = { asked += it; it in satisfied },
                signedTeamId = { teamReads++; signedTeam },
            )
    }

    @Test
    fun storeRequirementPinsAppleAnchorBothMarkersAndIdentifier() {
        assertEquals(
            "anchor apple generic and (certificate leaf[field.1.2.840.113635.100.6.1.9] exists " +
                "or certificate leaf[field.1.2.840.113635.100.6.1.25.1] exists) " +
                "and identifier \"app.tenshu.control.ios\"",
            MacPeerAttestation.storeRequirementFor("app.tenshu.control.ios"),
        )
    }

    @Test
    fun developerSignedPeerIsAdmittedByTheTeamPinWithoutReadingTheSignedTeam() {
        val peer = FakePeer(satisfied = setOf(MacPeerAttestation.requirementFor(ours, hint)), signedTeam = null)

        val outcome = peer.decide()

        assertEquals(PeerAttestation.Attested(hint, ours), outcome)
        assertEquals(0, peer.teamReads, "the team-OU route must not need the signed team")
    }

    @Test
    fun storeSignedPeerOfOurTeamIsAdmitted() {
        val peer = FakePeer(satisfied = setOf(MacPeerAttestation.storeRequirementFor(hint)), signedTeam = ours)

        assertEquals(PeerAttestation.Attested(hint, ours), peer.decide())
    }

    @Test
    fun storeSignedPeerOfAnotherTeamIsRejected() {
        // ⚠⚠ The case the team check exists for: another developer's store app carrying a
        // helper signed as one of our unregistered nested identifiers.
        val peer = FakePeer(
            satisfied = setOf(MacPeerAttestation.storeRequirementFor("tray-agent")),
            signedTeam = "57T9237FN3",
        )

        val outcome = peer.decide(claimed = "tray-agent")

        assertIs<PeerAttestation.Unattested>(outcome)
        assertTrue(outcome.reason.contains("57T9237FN3"), outcome.reason)
    }

    @Test
    fun storeSignedPeerWithNoSignedTeamIsRejected() {
        val peer = FakePeer(satisfied = setOf(MacPeerAttestation.storeRequirementFor(hint)), signedTeam = null)

        assertIs<PeerAttestation.Unattested>(peer.decide())
    }

    @Test
    fun ourSignedTeamWithoutTheStoreSignatureIsRejectedAndNeverRead() {
        // An ad-hoc or foreign-signed binary: no requirement holds. Whatever team it might
        // self-assert is never consulted.
        val peer = FakePeer(satisfied = emptySet(), signedTeam = ours)

        val outcome = peer.decide()

        assertIs<PeerAttestation.Unattested>(outcome)
        assertTrue(outcome.reason.contains("not signed by"), outcome.reason)
        assertEquals(0, peer.teamReads)
    }

    @Test
    fun storeSignatureForADifferentIdentifierDoesNotAdmitTheHint() {
        // The store requirement must name the claimed identifier, not just "some store app".
        val peer = FakePeer(
            satisfied = setOf(MacPeerAttestation.storeRequirementFor("com.example.other")),
            signedTeam = ours,
        )

        assertIs<PeerAttestation.Unattested>(peer.decide())
    }

    @Test
    fun storeSignedTeamMustBeOneTheIdentifierAcceptsNotAnyTeam() {
        val peer = FakePeer(satisfied = setOf(MacPeerAttestation.storeRequirementFor(hint)), signedTeam = ours)

        assertIs<PeerAttestation.Unattested>(peer.decide(teams = setOf("ABCDE12345")))
        assertEquals(
            PeerAttestation.Attested(hint, ours),
            peer.decide(teams = setOf("ABCDE12345", ours)),
        )
    }

    @Test
    fun identifierNoTeamVouchesForIsRejectedBeforeAnySignatureCheck() {
        val peer = FakePeer(satisfied = setOf(MacPeerAttestation.storeRequirementFor(hint)), signedTeam = ours)

        assertIs<PeerAttestation.Unattested>(peer.decide(teams = emptySet()))
        assertTrue(peer.asked.isEmpty())
        assertEquals(0, peer.teamReads)
    }

    /**
     * The requirement strings against real Apple signatures, via `codesign -R`. Self-skips
     * unless macOS and a TestFlight-installed Control are present (the maintainer's Mac) — so
     * elsewhere, including CI, the store strings are pinned only by
     * [storeRequirementPinsAppleAnchorBothMarkersAndIdentifier]. ⚠ This checks the bundles **on
     * disk** (`codesign -R`), not a running guest via `SecCodeCheckValidity`.
     *
     * Both directions: the TestFlight copy passes the store requirement and fails the team-OU
     * one (the defect), while an Apple system app fails the store requirement.
     */
    @Test
    fun requirementsAgainstRealAppleSignedBundles() {
        if (!ObjC.isAvailable) return
        val testFlightControl = File("/Applications/TenshuControl 2.app")
        val unrelated = File("/System/Applications/Calculator.app")
        if (!testFlightControl.exists() || !unrelated.exists()) return
        if (!codesignInfo(testFlightControl).contains("Authority=TestFlight Beta Distribution")) return

        assertTrue(satisfiesOnDisk(testFlightControl, MacPeerAttestation.storeRequirementFor(hint)))
        assertFalse(
            satisfiesOnDisk(testFlightControl, MacPeerAttestation.requirementFor(ours, hint)),
            "a TestFlight copy must not satisfy the team-OU pin — if it does, this test is stale",
        )
        assertTrue(codesignInfo(testFlightControl).contains("TeamIdentifier=$ours"))

        // The nested helper is the case route 2 exists for: its identifier is not registered
        // with Apple, and it is re-signed by Apple all the same.
        val trayAgent = File(testFlightControl, "Contents/Helpers/tray-agent")
        if (trayAgent.exists()) {
            assertTrue(satisfiesOnDisk(trayAgent, MacPeerAttestation.storeRequirementFor("tray-agent")))
            assertTrue(codesignInfo(trayAgent).contains("TeamIdentifier=$ours"))
        }

        assertFalse(
            satisfiesOnDisk(unrelated, MacPeerAttestation.storeRequirementFor("com.apple.calculator")),
        )
    }

    private fun satisfiesOnDisk(bundle: File, requirement: String): Boolean =
        ProcessBuilder("codesign", "-v", "-R=$requirement", bundle.path)
            .redirectErrorStream(true).start()
            .also { it.inputStream.readAllBytes() }
            .waitFor() == 0

    private fun codesignInfo(bundle: File): String =
        ProcessBuilder("codesign", "-dvv", bundle.path)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
}
