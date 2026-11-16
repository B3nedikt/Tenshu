package app.tenshu.control.ipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstPartyPublisherPolicyTest {

    private val publishers = setOf("4k2m9q8vxz1ab")

    @Test
    fun ourPackagesGetOurPublishers_andNothingElseGetsAny() {
        val policy = FirstPartyPublisherPolicy(publishers, knownPackages = setOf("Tenshu.Drive"))
        assertEquals(publishers, policy.acceptedPublishersFor("Tenshu.Drive"))
        assertTrue(policy.acceptedPublishersFor("Tenshu.Unknown").isEmpty())
        assertTrue(policy.acceptedPublishersFor("").isEmpty())
    }

    /** The default build has no publisher configured: nothing attests, the strict gate refuses. */
    @Test
    fun anUnconfiguredBuildTrustsNoPublisher() {
        assertTrue(FirstPartyPublisherPolicy(emptySet()).acceptedPublishersFor("Tenshu.Drive").isEmpty())
    }
}
