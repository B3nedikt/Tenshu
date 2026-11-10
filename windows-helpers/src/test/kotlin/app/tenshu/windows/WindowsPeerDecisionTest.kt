package app.tenshu.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Windows peer-gate decision, pure — runs on every host. */
class WindowsPeerDecisionTest {

    private val ours = "4k2m9q8vxz1ab" // a well-formed publisher id
    private val policy = PeerPublisherPolicy { name -> if (name == "Tenshu.Drive") setOf(ours) else emptySet() }
    private val now = 1_800_000_000_000L

    private fun facts(
        family: String? = "Tenshu.Drive_$ours",
        createdAt: Long = now - 60_000,
        alive: Boolean = true,
    ) = WindowsPeerFacts(
        pid = 4242,
        createdAtMillis = createdAt,
        alive = alive,
        packageFamilyName = family,
        imagePath = "C:\\Program Files\\WindowsApps\\Tenshu.Drive\\TenshuDrive.exe",
    )

    @Test
    fun ourPackageFromOurPublisherIsAttested() {
        assertEquals(
            WindowsPeerAttestation.Attested("Tenshu.Drive", ours),
            WindowsPeerDecision.decide(facts(), now, policy),
        )
    }

    /** ⚠⚠ The whole point of D1: no package identity, no attestation — whatever signs the binary. */
    @Test
    fun aProcessWithoutPackageIdentityIsUnattested() {
        val outcome = assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(family = null), now, policy))
        assertTrue("no package identity" in outcome.reason, outcome.reason)
    }

    /** Someone else's package with our name: the publisher half is what makes it ours. */
    @Test
    fun ourNameFromAnotherPublisherIsUnattested() {
        assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(family = "Tenshu.Drive_8wekyb3d8bbwe"), now, policy))
    }

    @Test
    fun aPackageWeDoNotShipIsUnattested_evenFromOurPublisher() {
        assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(family = "Tenshu.Unknown_$ours"), now, policy))
    }

    /** ⚠⚠ The pid-reuse answer: the real peer existed before verification began. */
    @Test
    fun aProcessCreatedAfterTheAcceptIsARecycledPid() {
        val outcome = assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(createdAt = now + 1), now, policy))
        assertTrue("recycled pid" in outcome.reason, outcome.reason)
    }

    @Test
    fun anExitedPeerIsUnattested() {
        assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(alive = false), now, policy))
    }

    @Test
    fun malformedFamilyNamesAreUnattested_notCrashes() {
        for (bad in listOf("", "_$ours", "Tenshu.Drive", "Tenshu.Drive_", "Tenshu.Drive_TOOSHORT", "Tenshu.Drive_4k2m9q8vxz1aI")) {
            assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(family = bad), now, policy), bad)
        }
    }

    /** An empty anchor — a build configured with no publisher — admits no one. */
    @Test
    fun anEmptyTrustAnchorAttestsNobody() {
        assertIs<WindowsPeerAttestation.Unattested>(WindowsPeerDecision.decide(facts(), now) { emptySet() }, "")
    }
}
