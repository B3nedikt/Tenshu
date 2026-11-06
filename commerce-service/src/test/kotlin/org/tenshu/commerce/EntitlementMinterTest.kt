package org.tenshu.commerce

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The minted record's signature must verify under the publisher key the on-device path looks up
 * (`EntitlementSignature.publicKeysFor(ISSUER_ID)`) — proving a Phase-1 mint would be accepted on
 * sync (`commerce-service-phase1-plan.md` §3). Uses the committed dev seed; its public half is
 * pinned in `EntitlementSignature.COMMERCE_PUBLIC_KEY` and guarded by `EntitlementSignatureKeyTest`.
 */
class EntitlementMinterTest {

    private val ed = BouncyCastleEd25519Backend()
    private val config = CommerceConfig.fromEnv { null } // all defaults: dev seed, tenshu.commerce
    private val minter = EntitlementMinter(config, IssuerKeypair(config.issuerSeed, ed))

    @Test
    fun mintedSignatureVerifiesUnderPublishedKey() {
        val now = 1_700_000_000_000L
        val minted = minter.mint(ownerId = "owner-1", productId = "tenshu.drive.pro", nowMs = now)

        val publicKeys = EntitlementSignature.publicKeysFor(config.issuerId)
        assertTrue(publicKeys.isNotEmpty(), "the commerce issuer must be registered in publicKeysFor")
        assertTrue(
            publicKeys.any { ed.verify(it, minted.message(), minted.signature) },
            "a minted entitlement's signature must verify under one of the pinned commerce keys",
        )
    }

    @Test
    fun mintStampsConfiguredIssuerAndTtl() {
        val now = 1_700_000_000_000L
        val minted = minter.mint(ownerId = "owner-2", productId = "tenshu.drive.pro", nowMs = now)

        assertEquals(config.issuerId, minted.publisherId)
        assertEquals(now, minted.validFrom)
        assertEquals(now + config.entitlementTtlHours * 60L * 60L * 1_000L, minted.validUntil)
    }
}
