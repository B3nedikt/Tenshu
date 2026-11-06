package app.tenshu.timeline.data.wifi

import org.tenshu.core.common.util.sha256

/**
 * Turns a raw BSSID into the opaque `networkTag` that rides in the realm
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §9.3 / F4).
 *
 * The lookup is exact-match, so a hash is functionally identical to the raw value. What it buys:
 * the raw MACs of the user's home, employer and friends' routers never sit in plaintext in the
 * realm, so they cannot surface through a diagnostics export, an error report, a future data export
 * or a backup inspection — the paths that actually leak things.
 *
 * ⚠⚠ **It is not a defence against an attacker holding the realm plaintext**, and the design says so
 * plainly (§9.3): the BSSID space is small and OUI-constrained, so such an attacker can brute-force
 * the mapping. That is acceptable because **the realm already holds the exact coordinates of every
 * place the user dwells and a polyline of every trip** — a network identifier adds no new *class* of
 * information. What syncing changes is blast radius, one device to N.
 *
 * ⚠ **No salt, deliberately — a change from the §9.3 sketch.** A salt would have to ride in the realm
 * for other devices to compute the same tag, so it sits beside the tags it is meant to protect and
 * buys nothing against the only adversary that could use it. It would, however, add a synced secret
 * with its own genesis, rotation and "what if a device has not pulled it yet" problems. A fixed
 * domain separator is exactly as strong against the threats hashing actually addresses, and has none
 * of that machinery.
 */
internal object NetworkTag {

    /**
     * ⚠ Domain-separated so a tag can never collide with, or be replayed as, a digest computed
     * anywhere else in the system.
     */
    private const val DOMAIN = "tenshu:place-network:bssid:"

    /** The tag for [bssid]; case-normalised, since BSSIDs come back with mixed casing across reads. */
    fun of(bssid: String): String =
        sha256((DOMAIN + bssid.lowercase()).encodeToByteArray()).toHexString()

    private fun ByteArray.toHexString(): String =
        joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
