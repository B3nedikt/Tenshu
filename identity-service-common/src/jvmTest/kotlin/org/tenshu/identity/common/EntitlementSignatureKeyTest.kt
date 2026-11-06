package org.tenshu.identity.common

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards that the pinned [EntitlementSignature.DEV_PUBLIC_KEY] is the genuine
 * Ed25519 public half of [EntitlementSignature.DEV_SEED] — so the verifier (which
 * only holds the public key) accepts what the signer (which holds the seed)
 * produces (storage-entitlement-sync.md slice 4a).
 */
class EntitlementSignatureKeyTest {

    @Test
    fun devPublicKey_isDerivedFromDevSeed() {
        val derived = BouncyCastleEd25519Backend().derivePublicKey(EntitlementSignature.DEV_SEED)
        assertContentEquals(EntitlementSignature.DEV_PUBLIC_KEY, derived)
    }

    /**
     * Guards that the pinned [EntitlementSignature.COMMERCE_PUBLIC_KEY] is the genuine Ed25519
     * public half of the commerce issuer's committed dev seed (`CommerceConfig.DEV_ISSUER_SEED`,
     * `ByteArray(32){ (it + 65) }`). The seed literal is reproduced here rather than depended on:
     * `:identity-service` must never import the seed (only the public half is pinned), and the
     * commerce module is downstream of this one.
     */
    @Test
    fun commercePublicKey_isDerivedFromCommerceDevSeed() {
        val commerceDevSeed = ByteArray(32) { (it + 65).toByte() }
        val derived = BouncyCastleEd25519Backend().derivePublicKey(commerceDevSeed)
        assertContentEquals(EntitlementSignature.COMMERCE_PUBLIC_KEY, derived)
    }

    /**
     * ⚠⚠ The regression that motivated multi-key acceptance (2026-08-07): promoting the deployed
     * commerce issuer from the committed dev seed to a real `ISSUER_SEED` made every entitlement it
     * minted fail verification on every client, because the registry resolved `tenshu.commerce` to
     * exactly ONE key. `SyncProcessor` then dropped each record and the Services card sat on
     * "Setting up your storage" with a valid entitlement in the store.
     *
     * Both signers must be accepted. This pins the property with real signatures rather than by
     * comparing constants, so it fails if the *verification* path narrows again — not only if a
     * literal changes.
     */
    @Test
    fun bothCommerceSigners_areAccepted() {
        val ed = BouncyCastleEd25519Backend()
        val message = EntitlementSignature.message(
            ownerId = "owner-1",
            publisherId = EntitlementSignature.COMMERCE_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = 1L,
            validUntil = 2L,
        )
        val keys = EntitlementSignature.publicKeysFor(EntitlementSignature.COMMERCE_PUBLISHER_ID)

        // The dev seed a DEV_MODE=true stack signs with.
        val devSigned = ed.sign(ByteArray(32) { (it + 65).toByte() }, message)
        assertTrue(
            keys.any { ed.verify(it, message, devSigned) },
            "a DEV_MODE commerce stack's records must still verify",
        )

        // A production-shaped seed: signed by a key whose PUBLIC half is pinned, private half
        // ops-held. Reconstructing it here would mean committing the production seed, so this
        // asserts the pinned production key is genuinely in the accepted set instead.
        assertTrue(
            keys.any { it.contentEquals(EntitlementSignature.COMMERCE_PRODUCTION_PUBLIC_KEY) },
            "the production issuer key must be accepted for tenshu.commerce",
        )
        assertEquals(2, keys.size, "dev + production; a third signer needs a deliberate decision")
    }

    /**
     * `TENSHU_EXTRA_ISSUER` (`entitlement-trust-anchor-distribution.md` D3a): a build may trust ONE
     * more issuer — the dev lab's `tenshu.commerce.dev`, which signs with its own seed. Pinned with
     * real signatures, through the pure form, because an ordinary test build is configured with none.
     */
    @Test
    fun additionalIssuer_verifiesItsOwnRecords_andOnlyUnderItsOwnId() {
        val ed = BouncyCastleEd25519Backend()
        val seed = ByteArray(32) { (it + 7).toByte() }
        val additional = EntitlementSignature.AdditionalIssuer("tenshu.commerce.dev", ed.derivePublicKey(seed))
        val message = EntitlementSignature.message("owner-1", "tenshu.commerce.dev", "tenshu.drive.free", 1L, 2L)
        val signature = ed.sign(seed, message)

        val keys = EntitlementSignature.publicKeysFor("tenshu.commerce.dev", additional)
        assertTrue(keys.any { ed.verify(it, message, signature) }, "the additional issuer's own record must verify")

        // Without it in force the id is unknown — which is every production build.
        assertTrue(EntitlementSignature.publicKeysFor("tenshu.commerce.dev", additional = null).isEmpty())
        // And it answers for its own id alone.
        assertTrue(EntitlementSignature.publicKeysFor("tenshu.commerce.other", additional).isEmpty())
    }

    /**
     * ⚠⚠ The property the whole variable rests on: it ADDS an issuer, it can never change what a
     * built-in one is trusted under. An additional issuer that names `tenshu.commerce` must not get
     * its key into production's set — or a dev-lab build variable would be a way to mint entitlements
     * a production-pointed client believes.
     */
    @Test
    fun additionalIssuer_cannotShadowABuiltInPublisher() {
        val ed = BouncyCastleEd25519Backend()
        val attackerKey = ed.derivePublicKey(ByteArray(32) { (it + 99).toByte() })
        for (builtIn in listOf(EntitlementSignature.COMMERCE_PUBLISHER_ID, EntitlementSignature.DEV_PUBLISHER_ID)) {
            val shadow = EntitlementSignature.AdditionalIssuer(builtIn, attackerKey)
            val withShadow = EntitlementSignature.publicKeysFor(builtIn, shadow)
            val without = EntitlementSignature.publicKeysFor(builtIn, additional = null)
            assertEquals(without.size, withShadow.size, "$builtIn gained a key from an additional issuer")
            assertTrue(withShadow.none { it.contentEquals(attackerKey) }, "$builtIn now trusts the additional key")
        }
    }

    /** A release binary never brings the build-configured issuer into force, whatever was compiled in. */
    @Test
    fun releaseBuild_neverTrustsTheBuildConfiguredIssuer() {
        try {
            assertNull(EntitlementSignature.trustBuildConfiguredIssuer(isRelease = true))
            assertEquals(
                listOf(EntitlementSignature.DEV_PUBLISHER_ID, EntitlementSignature.COMMERCE_PUBLISHER_ID),
                EntitlementSignature.trustedPublisherIds(),
            )
            // A debug build trusts exactly what was compiled in — nothing, in an ordinary test build.
            assertEquals(
                EntitlementSignature.buildConfiguredIssuer?.publisherId,
                EntitlementSignature.trustBuildConfiguredIssuer(isRelease = false),
            )
        } finally {
            EntitlementSignature.trustBuildConfiguredIssuer(isRelease = true)
        }
    }

    /**
     * ⚠⚠ The web's guard. A bundle has no release variant, so what keeps a `tenshu.extraIssuer` line
     * left in `local.properties` out of a production image is provenance: under `explicitOnly` an
     * issuer is in force only when the build itself was handed it.
     */
    @Test
    fun explicitOnly_ignoresAnIssuerInheritedFromAStandingFile() {
        val issuer = EntitlementSignature.AdditionalIssuer("tenshu.commerce.dev", ByteArray(32))
        fun inForce(fromStanding: Boolean, isRelease: Boolean, explicitOnly: Boolean) =
            EntitlementSignature.issuerInForce(issuer, fromStanding, isRelease, explicitOnly)

        assertNull(inForce(fromStanding = true, isRelease = false, explicitOnly = true), "web + local.properties")
        assertEquals(issuer, inForce(fromStanding = false, isRelease = false, explicitOnly = true), "web + env/-P")
        // Android and desktop keep the local.properties route — their guard is the release signal.
        assertEquals(issuer, inForce(fromStanding = true, isRelease = false, explicitOnly = false))
        assertNull(inForce(fromStanding = true, isRelease = true, explicitOnly = false))
        assertNull(inForce(fromStanding = false, isRelease = true, explicitOnly = true))
    }

}
