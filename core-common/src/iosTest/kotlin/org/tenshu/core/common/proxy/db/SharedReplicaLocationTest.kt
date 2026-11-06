package org.tenshu.core.common.proxy.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The location policy behind [sharedReplicaDirectory] (`ios-background-scheduling.md` S0).
 *
 * ⚠ The device branch cannot be reached through [sharedReplicaDirectory] here: this suite runs on
 * the simulator, where [IS_IOS_DEVICE_BUILD] is false by construction. So the policy takes the
 * flag as a parameter and the tests drive both values.
 */
class SharedReplicaLocationTest {

    @Test
    fun aResolvedContainerWinsOnEveryBuild() {
        for (deviceBuild in listOf(true, false)) {
            assertEquals(
                "/group/objects",
                resolveReplicaDirectory(
                    groupDirectory = "/group/objects",
                    deviceBuild = deviceBuild,
                    subdirectory = "objects",
                    appLocalDirectory = { error("the app-local directory must not be consulted") },
                ),
            )
        }
    }

    @Test
    fun aDeviceBuildWithoutAContainerRefusesInsteadOfFallingBack() {
        // The regression this exists for: the fallback ran on every real iPhone for months, one
        // store per app under one device id, and nothing reported it.
        var appLocalConsulted = false
        val failure = assertFailsWith<SharedReplicaUnavailableException> {
            resolveReplicaDirectory(
                groupDirectory = null,
                deviceBuild = true,
                subdirectory = "objects",
                appLocalDirectory = {
                    appLocalConsulted = true
                    "/documents/objects"
                },
            )
        }
        assertFalse(appLocalConsulted, "a refused resolution must not even compute the app-local path")
        assertTrue(TENSHU_APP_GROUP in failure.message.orEmpty(), "the message names the missing group")
    }

    @Test
    fun theSimulatorAndTestHostKeepTheAppLocalFallback() {
        assertEquals(
            "/documents/objects",
            resolveReplicaDirectory(
                groupDirectory = null,
                deviceBuild = false,
                subdirectory = "objects",
                appLocalDirectory = { "/documents/objects" },
            ),
        )
    }

    @Test
    fun thisSuiteIsNotADeviceBuild() {
        // Pins the per-target actual: if the simulator slice ever answered true, every headless
        // suite that opens the default store location would throw.
        assertFalse(IS_IOS_DEVICE_BUILD)
    }
}
