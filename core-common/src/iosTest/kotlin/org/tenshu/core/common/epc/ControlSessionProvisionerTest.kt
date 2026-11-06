package org.tenshu.core.common.epc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the C3 provisioning-bridge writer logic against a fake Keychain seam (the real
 * `Sec*` calls need a running Keychain, so they're exercised on the simulator / device, not here —
 * mirroring `IosSecureStorageDelegateTest`). What's load-bearing and tested here: the **exact
 * Keychain coordinates** the `epc-session` item lives at.
 */
class ControlSessionProvisionerTest {

    private class FakeSharedKeychainWriter : SharedKeychainWriter {
        data class Written(val service: String, val account: String, val group: String, val value: ByteArray)

        var lastWrite: Written? = null
        var lastDelete: Triple<String, String, String>? = null
        var writeResult = true

        override fun write(service: String, account: String, accessGroup: String, value: ByteArray): Boolean {
            lastWrite = Written(service, account, accessGroup, value)
            return writeResult
        }

        override fun delete(service: String, account: String, accessGroup: String) {
            lastDelete = Triple(service, account, accessGroup)
        }
    }

    @Test
    fun provision_writes_the_epc_session_coordinates() {
        val fake = FakeSharedKeychainWriter()
        val provisioner = ControlSessionProvisioner(fake)

        val ok = provisioner.provision("a1b2c3")

        assertTrue(ok)
        val written = fake.lastWrite!!
        assertEquals("app.tenshu.control.session", written.service)
        assertEquals("epc-session", written.account)
        assertEquals("app.tenshu.shared", written.group)
        assertEquals("a1b2c3", written.value.decodeToString())
    }

    @Test
    fun provision_propagates_a_write_failure() {
        val fake = FakeSharedKeychainWriter().apply { writeResult = false }
        assertFalse(ControlSessionProvisioner(fake).provision("x"))
    }

    @Test
    fun clear_deletes_the_session_coordinates() {
        val fake = FakeSharedKeychainWriter()
        ControlSessionProvisioner(fake).clear()
        assertEquals(
            Triple("app.tenshu.control.session", "epc-session", "app.tenshu.shared"),
            fake.lastDelete,
        )
    }

    @Test
    fun coordinate_constants_are_stable() {
        assertEquals("app.tenshu.control.session", ControlSessionProvisioner.SERVICE)
        assertEquals("epc-session", ControlSessionProvisioner.ACCOUNT)
        assertEquals("app.tenshu.shared", ControlSessionProvisioner.ACCESS_GROUP)
    }
}
