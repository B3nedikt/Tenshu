package app.tenshu.control.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `control-app-catalogue.md` C4.
 *
 * ⚠ Both directions are asserted deliberately. A probe hardwired to `true` is exactly the bug
 * this replaces (`verified` was hardcoded — in the other direction — for the whole of v1), and
 * a suite that only checks the recorded case would pass against it happily.
 */
class BinderAttestedClientsTest {

    @Test
    fun aRecordedClientIsAttested() {
        val clients = BinderAttestedClients()
        clients.record("app.tenshu.drive.android")

        assertTrue(clients.isAttested("app.tenshu.drive.android"))
    }

    @Test
    fun anUnrecordedClientIsNotAttested() {
        val clients = BinderAttestedClients()
        clients.record("app.tenshu.drive.android")

        // Control's own in-process client never passes through the uid-derived path, so it is
        // not attested by *this* evidence — and must not be reported as if it were.
        assertFalse(clients.isAttested("app.tenshu.control"))
        assertFalse(BinderAttestedClients().isAttested("app.tenshu.drive.android"))
    }

    @Test
    fun recordingIsIdempotentPerClient() {
        val clients = BinderAttestedClients()
        // Called on every binder transaction, not once per session.
        repeat(3) { clients.record("app.tenshu.fit.android") }

        assertTrue(clients.isAttested("app.tenshu.fit.android"))
        assertFalse(clients.isAttested("app.tenshu.maps.android"))
    }
}
