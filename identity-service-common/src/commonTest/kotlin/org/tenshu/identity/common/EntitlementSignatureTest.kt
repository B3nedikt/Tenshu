package org.tenshu.identity.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Publisher-signature core (storage-entitlement-sync.md slice 4a) under **real**
 * Ed25519: a signature over the canonical [EntitlementSignature.message] verifies, and
 * any tamper / empty signature / unknown publisher is rejected. The `SyncProcessor`
 * verify-on-merge wiring is covered by `EntitlementVerifyOnMergeTest`.
 *
 * ⚠ **In `commonTest` (was `jvmTest`) so this runs on Kotlin/Native (iOS) too** — the
 * sign/verify + [EntitlementSignature.message] byte layout MUST be byte-identical across
 * the JVM (BouncyCastle), Native (libsodium) and JS (@noble) backends or a joiner's
 * `X-Entitlement-Proof` fails relay verification on one platform but not another (the
 * iOS-joiner `entitlement_invalid` class of bug). The backend comes from the production
 * [cryptoBackendsModule] so each target exercises its real impl rather than a JVM-only one.
 */
class EntitlementSignatureTest {

    private val ed: Ed25519Backend =
        koinApplication { modules(cryptoBackendsModule()) }.koin.get()
    private val context = createTestContext(
        createCoreTestRegistry().also { it.registerConfig(identityServiceCommonModule) },
    )

    private fun signWithDevKey(message: ByteArray): ByteArray =
        ed.sign(EntitlementSignature.DEV_SEED, message)

    @Test
    fun validDevSignature_verifies() {
        val message = EntitlementSignature.message(
            ownerId = "owner-1",
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = 100L,
            validUntil = 200L,
        )
        val signature = signWithDevKey(message)

        val publicKeys = EntitlementSignature.publicKeysFor(EntitlementSignature.DEV_PUBLISHER_ID)
        assertContentEquals(EntitlementSignature.DEV_PUBLIC_KEY, publicKeys.single())
        assertTrue(ed.verify(publicKeys.single(), message, signature))
    }

    @Test
    fun tamperedField_failsVerification() {
        val signed = EntitlementSignature.message(
            "owner-1", EntitlementSignature.DEV_PUBLISHER_ID, "tenshu.drive.free", 100L, 200L,
        )
        val signature = signWithDevKey(signed)
        // Same owner/window, different product — the signature must not carry over.
        val tampered = EntitlementSignature.message(
            "owner-1", EntitlementSignature.DEV_PUBLISHER_ID, "tenshu.drive.premium", 100L, 200L,
        )
        assertFalse(ed.verify(EntitlementSignature.DEV_PUBLIC_KEY, tampered, signature))
    }

    @Test
    fun emptySignature_failsVerification() {
        val message = EntitlementSignature.message(
            "owner-1", EntitlementSignature.DEV_PUBLISHER_ID, "tenshu.drive.free", 100L, 200L,
        )
        assertFalse(ed.verify(EntitlementSignature.DEV_PUBLIC_KEY, message, ByteArray(0)))
    }

    @Test
    fun unknownPublisher_hasNoKey() {
        assertTrue(EntitlementSignature.publicKeysFor("evil.corp").isEmpty())
    }

    @Test
    fun fieldsOf_extractsCanonicalFieldsAndMessage() {
        // Instants beyond the 32-bit range: exercises longToBigEndianBytes on each backend's
        // target (a Native Long-encoding drift here would desync the signed message).
        val validFrom = 1_784_000_000_000L
        val validUntil = 1_784_600_000_000L
        val signed = EntitlementSignature.message(
            "owner-1", EntitlementSignature.DEV_PUBLISHER_ID, "tenshu.drive.free", validFrom, validUntil,
        )
        val signature = signWithDevKey(signed)
        val entitlement = Entitlement.create(
            context = context,
            ownerId = "owner-1",
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = validFrom,
            validUntil = validUntil,
            publisherSignature = signature,
        )

        val fields = assertNotNull(EntitlementSignature.fieldsOf(entitlement))
        assertContentEquals(signed, fields.message(), "fieldsOf must rebuild the exact signed bytes")
        assertContentEquals(signature, fields.signature)
        assertTrue(ed.verify(EntitlementSignature.DEV_PUBLIC_KEY, fields.message(), fields.signature))
    }
}
