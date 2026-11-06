package app.tenshu.hub

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.ParsedPairingUri

/**
 * H2 provisioning (hub proposal §11.3): idempotence, label↔runtime identity binding,
 * `--rotate` scope (secret only, never the device identity), and artifact emission.
 */
class HubProvisioningTest {

    @Test
    fun provision_isIdempotent_andRotateRegeneratesOnlyTheSecret() = runBlocking {
        val dataRoot = createTempDirectory("hub-provision-test").toFile()
        val runtime = HubRuntime.assemble(dataRoot = dataRoot)
        try {
            assertFalse(HubProvisioning.isProvisioned(dataRoot))

            val first = HubProvisioning.provision(dataRoot, runtime.syncManager)
            assertTrue(first.generatedSecret)
            assertTrue(HubProvisioning.isProvisioned(dataRoot))
            assertTrue(first.uriFile.isFile, "pairing-uri.txt must be written")
            assertTrue(first.qrFile.isFile, "pairing-qr.png must be written")
            assertTrue(first.qrFile.length() > 0, "the QR PNG must have content")
            assertEquals(first.uri, first.uriFile.readText().trim())

            // The label parses and carries the RUNTIME's identity: printed d = the
            // proxy's device id, printed g = its A4 signing key (the whole point —
            // the standard registration delegation works off the printed fields).
            val parsed = assertIs<ParsedPairingUri.Hub>(PairingUri.parse(first.uri))
            assertEquals(runtime.syncManager.currentDeviceId, parsed.hubDeviceId)
            assertContentEquals(
                runtime.syncManager.getDeviceSigningKeyPair().publicKey,
                parsed.hubVerifyKey,
            )

            // Idempotent: a re-run re-prints, it does NOT rotate silently.
            val second = HubProvisioning.provision(dataRoot, runtime.syncManager)
            assertFalse(second.generatedSecret)
            assertEquals(first.uri, second.uri)
            assertEquals(first.secretBase32, second.secretBase32)

            // --rotate mints a fresh secret but keeps the device identity + verify key.
            val rotated = HubProvisioning.provision(dataRoot, runtime.syncManager, rotate = true)
            assertTrue(rotated.generatedSecret)
            assertNotEquals(first.secretBase32, rotated.secretBase32)
            val rotatedParsed = assertIs<ParsedPairingUri.Hub>(PairingUri.parse(rotated.uri))
            assertEquals(parsed.hubDeviceId, rotatedParsed.hubDeviceId)
            assertContentEquals(parsed.hubVerifyKey, rotatedParsed.hubVerifyKey)

            // printedUri() re-derives the same label the artifacts carry.
            assertEquals(rotated.uri, HubProvisioning.printedUri(dataRoot, runtime.syncManager))
        } finally {
            runtime.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun base32_roundTrips() {
        for (size in listOf(1, 5, 16, 32)) {
            val bytes = ByteArray(size) { (it * 7 + size).toByte() }
            assertContentEquals(
                bytes,
                HubProvisioning.Base32.decode(HubProvisioning.Base32.encode(bytes)),
            )
        }
    }
}
