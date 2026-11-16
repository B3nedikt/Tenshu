package app.tenshu.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MsixPublisherIdTest {

    /** The known answer: every inbox Microsoft package family name ends in `_8wekyb3d8bbwe`. */
    @Test
    fun microsoftsPublisherIsTheKnownId() {
        assertEquals(
            "8wekyb3d8bbwe",
            MsixPublisherId.of("CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US"),
        )
    }

    /** Whatever it derives must be something [WindowsPeerDecision] accepts as well-formed. */
    @Test
    fun theDerivedIdIsAlwaysAWellFormedPublisherId() {
        val id = MsixPublisherId.of("CN=Tenshu Local Test")
        assertEquals(13, id.length)
        assertTrue(id.all { it in "0123456789abcdefghjkmnpqrstvwxyz" }, id)
        val attested = WindowsPeerDecision.decide(
            WindowsPeerFacts(1, 0, true, "Tenshu.Drive_$id", null, WindowsPackageOrigin.DEVELOPER_SIGNED),
            1,
        ) { setOf(id) }
        assertEquals(WindowsPeerAttestation.Attested("Tenshu.Drive", id), attested)
    }

    @Test
    fun theStringIsExact_noNormalisation() {
        assertNotEquals(MsixPublisherId.of("CN=Tenshu Local Test"), MsixPublisherId.of("CN=Tenshu Local Test "))
    }
}
