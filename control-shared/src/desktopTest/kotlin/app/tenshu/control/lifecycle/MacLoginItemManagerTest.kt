package app.tenshu.control.lifecycle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacLoginItemManagerTest {
    @Test
    fun sandboxedBundleResolvesItsLoginItemHelper() {
        val root = Files.createTempDirectory("tenshu-login-item").toFile()
        try {
            val executable = root.resolve(
                "TenshuControl.app/Contents/MacOS/TenshuControl",
            ).apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            val helper = root.resolve(
                "TenshuControl.app/Contents/Library/LoginItems/" +
                    "TenshuControlLoginItem.app/Contents/MacOS/TenshuControlLoginItem",
            ).apply {
                parentFile.mkdirs()
                writeText("helper")
            }
            assertTrue(helper.isFile)

            val manager = MacLoginItemManager(
                daemonExecutablePath = executable.absolutePath,
                sandboxed = true,
                registration = FakeRegistration(status = MacLoginItemManager.STATUS_ENABLED),
            )

            assertTrue(manager.isAvailable())
            assertTrue(manager.isAutostartEnabled())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsandboxedBundleDoesNotUseTheLoginItem() {
        val manager = MacLoginItemManager(
            daemonExecutablePath = "/Applications/TenshuControl.app/Contents/MacOS/TenshuControl",
            sandboxed = false,
            registration = FakeRegistration(),
        )

        assertFalse(manager.isAvailable())
        assertFalse(manager.isAutostartEnabled())
        assertFalse(manager.enable())
        assertFalse(manager.disable())
    }

    @Test
    fun approvalIsNotDisplayedAsEnabledBeforeTheUserCompletesIt() {
        val root = Files.createTempDirectory("tenshu-login-item-approval").toFile()
        try {
            val executable = root.resolve(
                "TenshuControl.app/Contents/MacOS/TenshuControl",
            ).apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            root.resolve(
                "TenshuControl.app/Contents/Library/LoginItems/" +
                    "TenshuControlLoginItem.app/Contents/MacOS/TenshuControlLoginItem",
            ).apply {
                parentFile.mkdirs()
                writeText("helper")
            }

            val manager = MacLoginItemManager(
                daemonExecutablePath = executable.absolutePath,
                sandboxed = true,
                registration = FakeRegistration(
                    status = MacLoginItemManager.STATUS_REQUIRES_APPROVAL,
                ),
            )

            assertFalse(manager.isAutostartEnabled())
            assertTrue(manager.isApprovalRequired())
            assertFalse(manager.enable())
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeRegistration(
        private var status: String? = MacLoginItemManager.STATUS_UNKNOWN,
        private val available: Boolean = true,
    ) : MacLoginItemRegistration {
        override fun isAvailable(): Boolean = available

        override fun status(): String? = status

        override fun register(): Boolean {
            status = MacLoginItemManager.STATUS_REQUIRES_APPROVAL
            return true
        }

        override fun unregister(): Boolean {
            status = MacLoginItemManager.STATUS_NOT_REGISTERED
            return true
        }
    }
}
