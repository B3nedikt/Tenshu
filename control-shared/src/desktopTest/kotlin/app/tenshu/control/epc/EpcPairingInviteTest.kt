package app.tenshu.control.epc

import org.tenshu.core.common.epc.EpcPairingUri
import org.tenshu.core.common.util.deriveEpcRendezvousSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpcPairingInviteTest {

    private val secret = ByteArray(16) { (it * 7 + 1).toByte() }

    @Test
    fun uri_decodes_back_to_the_same_secret() {
        val invite = buildEpcPairingInvite("https://relay.tenshu.app", secret)
        assertTrue(invite.uri.startsWith(EpcPairingUri.PREFIX), "uri: ${invite.uri}")
        val decoded = EpcPairingUri.decode(invite.uri)
        assertEquals("https://relay.tenshu.app", decoded.relayBaseUrl)
        assertEquals(secret.toList(), decoded.pairingSecret.toList())
    }

    @Test
    @OptIn(ExperimentalStdlibApi::class)
    fun session_id_is_the_hex_of_the_derived_rendezvous_id() {
        val invite = buildEpcPairingInvite("https://r.example", secret)
        // The exact encoding the app side + relay route must agree on (§10.1).
        assertEquals(deriveEpcRendezvousSessionId(secret).toHexString(), invite.sessionId)
    }

    @Test
    @OptIn(ExperimentalStdlibApi::class)
    fun a_peer_decoding_the_uri_derives_the_same_session_id() {
        val invite = buildEpcPairingInvite("https://r.example", secret)
        val peerSecret = EpcPairingUri.decode(invite.uri).pairingSecret
        assertEquals(invite.sessionId, deriveEpcRendezvousSessionId(peerSecret).toHexString())
    }

    @Test
    fun distinct_secrets_yield_distinct_session_ids() {
        val a = buildEpcPairingInvite("https://r", ByteArray(16) { 1 })
        val b = buildEpcPairingInvite("https://r", ByteArray(16) { 2 })
        assertTrue(a.sessionId != b.sessionId)
        assertTrue(a.uri != b.uri)
    }
}
