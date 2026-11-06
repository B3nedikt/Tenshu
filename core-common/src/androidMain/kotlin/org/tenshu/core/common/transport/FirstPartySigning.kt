package org.tenshu.core.common.transport

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.security.MessageDigest

/**
 * Which Android packages are first-party Tenshu apps, and how to tell a real one from an app that
 * merely took the package name (threat model T-C-20, T-C-19).
 *
 * A package name proves nothing on its own. Play keeps names unique, but a sideloaded app can claim
 * any name that is not installed yet. So an app counts as first-party only if it has one of the ten
 * names below **and** is signed by one of these:
 *
 * - **that app's Play signing key** ([PLAY_SIGNING_CERT_SHA256]). Play generated a separate key for
 *   each app (`docs/how-to/android-release.md` "Which key signs what"). That is why a
 *   `protectionLevel="signature"` permission cannot work between them, and why `knownSigner`, which
 *   needs API 31 with minSdk at 24, cannot either.
 * - **the side-channel key** ([SIDE_CHANNEL_SIGNING_CERT_SHA256]): one key of ours for all ten apps,
 *   signing every copy that does not come from Play (the APK download on tenshu.app, our own
 *   F-Droid repo). Pinned so apps from **different** channels admit each other: Play Control with a
 *   downloaded Drive, or the reverse. `null` until the key exists; while it is, nothing extra is
 *   admitted.
 * - **the same certificate as the app asking.** One rule covers debug builds (every app is signed by
 *   the local debug key, so the smoke gate and a developer's device work) and two copies from the
 *   side channel. In a Play build it changes nothing: no other app holds Control's Play key.
 *
 * ⚠⚠ Checked with `hasSigningCertificate` (through [PackageInfoCompat.hasSignatures]), never by
 * comparing signature bytes. It also matches a key in the app's rotation history. Play offers a
 * signing-key upgrade every two years or so, and its post-quantum hybrid signing on Android 17
 * presents the `deployment_cert` this table pins as a past signer. So an upgraded key does not
 * lock out installed apps. ⚠ Below API 28 there is no rotation history: `PackageInfoCompat` compares
 * the current signers' digests, so a pinned key works there only until it is rotated.
 *
 * ⚠ Play's *internal app sharing* links re-sign with a different key, so builds installed that
 * way are refused. The internal, closed and open testing tracks use the real key.
 */
object FirstPartySigning {

    /** Control's Android application id: the bind target, and the package-visibility subject. */
    const val CONTROL_PACKAGE = "app.tenshu.control.android"

    /**
     * Each app's Play app-signing certificate (`deployment_cert`), SHA-256. Read from the Play API
     * (`generatedApks/<versionCode>`, `certificateSha256Hash`) on 2026-10-29. ⚠ It must equal the
     * table in `docs/how-to/android-release.md`: `FirstPartySigningTest` checks both.
     */
    val PLAY_SIGNING_CERT_SHA256: Map<String, String> = mapOf(
        CONTROL_PACKAGE to
            "01:25:4D:FB:5D:67:A3:DF:F9:53:A2:6A:44:34:E7:DD:00:CB:18:C6:A8:EF:8F:93:EC:2B:BC:07:5A:CD:8D:2C",
        "app.tenshu.drive.android" to
            "3D:9E:B2:4D:B3:51:25:A3:73:08:60:63:4A:75:4A:CA:83:AE:F0:3D:D2:76:89:EB:80:40:A0:D9:13:70:F1:39",
        "app.tenshu.maps.android" to
            "4C:BF:59:E7:CF:DD:57:D7:2F:F5:E6:9E:9D:56:06:A9:68:01:5B:F8:29:E0:4F:EC:4C:93:7E:09:48:8A:DB:F6",
        "app.tenshu.timeline.android" to
            "53:FE:A2:06:11:DE:19:6E:92:49:82:21:17:AD:65:8D:BA:A9:D0:18:73:52:B5:60:4C:91:9B:AD:17:22:30:97",
        "app.tenshu.calendar.android" to
            "11:39:11:51:33:05:3C:C2:49:1E:C2:3F:F3:7B:CD:FB:1C:71:E8:F5:EE:9A:BB:74:71:1B:B8:55:9F:DA:70:76",
        "app.tenshu.contacts.android" to
            "4C:59:1A:EF:1E:8E:42:D6:8D:67:CF:97:A7:A5:63:2A:68:1A:11:B9:C8:4E:55:D8:9A:54:5A:E4:65:87:7D:4B",
        "app.tenshu.fit.android" to
            "93:7A:E9:AE:D3:A1:77:16:62:12:91:2B:A7:7D:6F:2C:12:31:2A:10:E0:26:66:0C:27:5E:94:2D:89:FD:71:28",
        "app.tenshu.photos.android" to
            "43:42:15:C7:A9:7F:0C:80:C7:56:24:BA:32:7B:9B:37:46:83:D2:47:EF:BC:0B:8F:36:14:68:70:AD:73:F0:05",
        "app.tenshu.mia.android" to
            "35:E6:05:C3:3A:5C:26:D3:7A:6B:67:5B:5A:8B:70:AA:49:68:96:E3:82:27:92:E8:C5:06:F4:B4:77:C4:15:CE",
        "app.tenshu.home.android" to
            "52:EB:57:37:A2:25:7B:69:44:79:B0:EB:C0:F8:3E:CB:42:91:73:DF:3F:08:66:C0:F4:A9:64:BD:3B:2D:0F:65",
    )

    /**
     * The side-channel signing certificate, SHA-256, colon-separated like [PLAY_SIGNING_CERT_SHA256]
     * (`docs/how-to/android-release.md` "The side-channel key"). The same for all ten apps.
     *
     * ⚠⚠ **Only** the side-channel key's digest belongs here (created 2026-11-06 with
     * `scripts/android-release.sh --init-key --direct`; it lives in the maintainer's Keychain).
     * Never the upload key's (Google re-signs everything it delivers, so nothing installed carries
     * it), never a debug key's (each
     * machine has its own, and pinning one admits any app built on that machine). It must equal
     * the release doc's line: `FirstPartySigningTest` checks both.
     *
     * ⚠ Pin it in the release **before** the first downloadable APK ships: a Play app learns the
     * digest only from an update, so Play users must already hold it when the first downloaded
     * Control or Drive arrives. Until then, mixing channels is refused, in both directions.
     */
    val SIDE_CHANNEL_SIGNING_CERT_SHA256: String? =
        "C4:D4:81:6E:D4:65:6D:59:3A:6B:FA:A6:ED:35:04:AE:C3:4B:95:C8:26:16:0B:CF:2B:C2:A5:77:3B:9D:AC:F3"

    /** The ten first-party package names. */
    val PACKAGES: Set<String> get() = PLAY_SIGNING_CERT_SHA256.keys

    /**
     * The decision itself, with the package manager's two answers passed in so it can be tested on
     * the host. [hasPinnedCertificate] is asked about [packageName]'s pinned Play digest, then about
     * [sideChannelDigest] when there is one; [sharesSignerWithSelf] whether [packageName] is signed
     * like the app asking. [sideChannelDigest] is a parameter only so a test can supply its own
     * test key.
     */
    fun isFirstParty(
        packageName: String,
        hasPinnedCertificate: (sha256: ByteArray) -> Boolean,
        sharesSignerWithSelf: () -> Boolean,
        sideChannelDigest: String? = SIDE_CHANNEL_SIGNING_CERT_SHA256,
    ): Boolean {
        val pinned = PLAY_SIGNING_CERT_SHA256[packageName] ?: return false
        return hasPinnedCertificate(digestBytes(pinned)) ||
            (sideChannelDigest != null && hasPinnedCertificate(digestBytes(sideChannelDigest))) ||
            sharesSignerWithSelf()
    }

    /**
     * Whether the installed [packageName] is a first-party Tenshu app, as seen by [selfPackage].
     *
     * ⚠ The caller must be able to see [packageName] (Android 11+ package visibility). Control sees
     * an app that binds it; an app sees Control through its `<queries>` entry. A package that is not
     * visible or not installed is answered `false`, never an exception.
     */
    fun isFirstParty(packageManager: PackageManager, packageName: String, selfPackage: String): Boolean =
        isFirstParty(
            packageName = packageName,
            hasPinnedCertificate = { sha256 ->
                runCatching {
                    PackageInfoCompat.hasSignatures(
                        packageManager,
                        packageName,
                        mapOf(sha256 to CERT_INPUT_SHA256),
                        false,
                    )
                }.getOrDefault(false)
            },
            sharesSignerWithSelf = {
                packageManager.checkSignatures(selfPackage, packageName) == PackageManager.SIGNATURE_MATCH
            },
        )

    /**
     * The SHA-256 digests of [packageName]'s current signers, for a refusal's log line only, so a
     * wrong pin can be read off logcat. Empty when the package cannot be read.
     */
    @Suppress("DEPRECATION")
    fun signerDigests(packageManager: PackageManager, packageName: String): List<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { formatDigest(sha256(it.toByteArray())) }
    }.getOrDefault(emptyList())

    /** `"01:25:…"` → its 32 bytes. */
    fun digestBytes(colonHex: String): ByteArray =
        colonHex.split(':').map { it.toInt(16).toByte() }.toByteArray()

    private fun formatDigest(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** `PackageManager.CERT_INPUT_SHA256` (API 28), inlined so it reads on every API level. */
    private const val CERT_INPUT_SHA256 = 1
}
