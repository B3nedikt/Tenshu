package app.tenshu.hub

import io.nayuki.qrcodegen.QrCode
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.sync.common.SyncManager

/**
 * H2 provisioning (hub proposal §11.3): the prototype stand-in for factory provisioning.
 * Generates — or, idempotently, re-reads — the material behind the hub's **printed label**:
 *
 *  - the device identity + static Ed25519 signing keypair, which are simply the proxy's
 *    OWN durable `DEVICE_ID` / `DEVICE_SIGNING_KEY` (minted through [SyncManager], stored
 *    via the hub's [FileWrappingKeyStore]-backed secret bucket) — the printed verify key
 *    IS the hub's A4 device key, so the standard offer-flow registration delegation works
 *    unchanged from the printed `d`/`g` fields;
 *  - a long-lived 128-bit pairing secret, stored as base32 text in `pairing-secret`
 *    (owner-only) beside the store. The secret is a *rendezvous capability*, not key
 *    material — its reference copy lives printed on the device's OUTSIDE — so a plain
 *    file (vs the secret bucket) is deliberate and keeps `provision` runnable without
 *    unlocking anything.
 *
 * Emits the printable artifacts: `pairing-uri.txt` (the `tenshu://hub?…` URI) and
 * `pairing-qr.png` (tape it to the Pi), plus the ASCII QR + a base32 manual-entry line
 * (Matter-style, for damaged labels) on the terminal via the caller.
 *
 * Idempotent: re-running prints the existing material. [rotate] regenerates the **secret
 * only** — the device identity/signing key must not rotate silently (they are the durable
 * A4 identity; rotating them is a wipe-and-reprovision, not a flag).
 */
object HubProvisioning {

    const val SECRET_FILE = "pairing-secret"
    const val URI_FILE = "pairing-uri.txt"
    const val QR_FILE = "pairing-qr.png"

    /** 128-bit CSPRNG pairing secret (§11.3, mirroring the offer flow's SECRET_BYTES). */
    private const val SECRET_BYTES = 16

    class ProvisionedLabel(
        val uri: String,
        val deviceId: String,
        val secretBase32: String,
        val uriFile: File,
        val qrFile: File,
        /** False when every artifact already existed (pure re-print run). */
        val generatedSecret: Boolean,
    )

    fun isProvisioned(dataRoot: File): Boolean = File(dataRoot, SECRET_FILE).isFile

    /** The printed URI, or `null` if this data dir was never provisioned. */
    fun printedUri(dataRoot: File, syncManager: SyncManager): String? {
        val secret = readSecret(dataRoot) ?: return null
        return PairingUri.hubUri(
            hubDeviceId = syncManager.currentDeviceId,
            hubVerifyKey = syncManager.getDeviceSigningKeyPair().publicKey,
            pairingSecret = secret,
        )
    }

    /**
     * Ensure the provisioned material exists (minting what is missing) and (re)write the
     * printable artifacts. [syncManager] must belong to a runtime rooted at [dataRoot].
     */
    fun provision(
        dataRoot: File,
        syncManager: SyncManager,
        rotate: Boolean = false,
    ): ProvisionedLabel {
        dataRoot.mkdirs()
        val secretFile = File(dataRoot, SECRET_FILE)
        val existing = if (rotate) null else readSecret(dataRoot)
        val secret = existing ?: nextSecureRandomBytes(SECRET_BYTES).also {
            writeOwnerOnly(secretFile, Base32.encode(it))
        }

        val uri = PairingUri.hubUri(
            hubDeviceId = syncManager.currentDeviceId,
            hubVerifyKey = syncManager.getDeviceSigningKeyPair().publicKey,
            pairingSecret = secret,
        )
        val uriFile = File(dataRoot, URI_FILE).apply { writeText(uri + "\n") }
        val qrFile = File(dataRoot, QR_FILE).also { writeQrPng(uri, it) }

        return ProvisionedLabel(
            uri = uri,
            deviceId = syncManager.currentDeviceId,
            secretBase32 = Base32.encode(secret),
            uriFile = uriFile,
            qrFile = qrFile,
            generatedSecret = existing == null,
        )
    }

    private fun readSecret(dataRoot: File): ByteArray? {
        val file = File(dataRoot, SECRET_FILE)
        if (!file.isFile) return null
        val text = file.readText().trim()
        if (text.isEmpty()) return null
        return Base32.decode(text)
    }

    private fun writeOwnerOnly(file: File, content: String) {
        file.writeText(content + "\n")
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem — best effort only (prototype posture; the printed
            // label on the device's outside is the same information anyway).
        }
    }

    private fun writeQrPng(text: String, target: File, modulePixels: Int = 8, quietModules: Int = 4) {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val modules = qr.size + 2 * quietModules
        val sizePx = modules * modulePixels
        val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                val mx = x / modulePixels - quietModules
                val my = y / modulePixels - quietModules
                val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.getModule(mx, my)
                image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
            }
        }
        ImageIO.write(image, "png", target)
    }

    // RFC 4648 base32, unpadded, A–Z2–7 — the same alphabet the URI fields use
    // (core-common's encoder is `internal`, so the hub keeps this tiny local twin for
    // the secret file + the manual-entry line under the QR).
    internal object Base32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 8 + 4) / 5)
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    out.append(ALPHABET[(buffer ushr bits) and 0x1F])
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
            return out.toString()
        }

        fun decode(encoded: String): ByteArray {
            val out = ByteArray(encoded.length * 5 / 8)
            var buffer = 0
            var bits = 0
            var index = 0
            for (char in encoded) {
                val value = ALPHABET.indexOf(char.uppercaseChar())
                require(value >= 0) { "invalid base32 character '$char'" }
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out[index++] = ((buffer ushr bits) and 0xFF).toByte()
                }
            }
            return out
        }
    }
}
