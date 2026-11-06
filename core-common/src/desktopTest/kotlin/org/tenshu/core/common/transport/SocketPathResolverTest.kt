package org.tenshu.core.common.transport

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketPathResolverTest {

    private val savedDir = System.getProperty(SocketPathResolver.DIR_PROPERTY)
    private val savedTeam = System.getProperty(SocketPathResolver.TEAM_ID_PROPERTY)

    @AfterTest
    fun restore() {
        restore(SocketPathResolver.DIR_PROPERTY, savedDir)
        restore(SocketPathResolver.TEAM_ID_PROPERTY, savedTeam)
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    private fun isMacOs() =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    @Test
    fun explicitDirOverrideWins_forBothSockets() {
        System.clearProperty(SocketPathResolver.TEAM_ID_PROPERTY)
        System.setProperty(SocketPathResolver.DIR_PROPERTY, "/tmp/tenshu-test")

        assertEquals("/tmp/tenshu-test", SocketPathResolver.resolveDir())
        assertEquals("/tmp/tenshu-test/ipc.sock", SocketPathResolver.resolve())
        assertEquals("/tmp/tenshu-test/bridge.sock", SocketPathResolver.resolveBridge())
    }

    @Test
    fun fallsBackToHomeTenshu_whenNoOverrideAndNoTeamId() {
        System.clearProperty(SocketPathResolver.DIR_PROPERTY)
        System.clearProperty(SocketPathResolver.TEAM_ID_PROPERTY)

        val expected = System.getProperty("user.home") + "/.tenshu/ipc.sock"
        assertEquals(expected, SocketPathResolver.resolve())
    }

    @Test
    fun usesAppGroupContainer_onMacOsWithTeamId() {
        // OS-dependent branch: assert the group-container path on macOS, and the
        // dev fallback elsewhere (Linux CI), so the test is deterministic on both.
        System.clearProperty(SocketPathResolver.DIR_PROPERTY)
        System.setProperty(SocketPathResolver.TEAM_ID_PROPERTY, "ABCDE12345")

        val dir = SocketPathResolver.resolveDir()
        if (isMacOs()) {
            assertTrue(
                dir.endsWith("/Library/Group Containers/ABCDE12345.app.tenshu.shared"),
                "expected App Group container path, got: $dir"
            )
        } else {
            assertTrue(dir.endsWith("/.tenshu"), "expected dev fallback, got: $dir")
        }
    }
}
