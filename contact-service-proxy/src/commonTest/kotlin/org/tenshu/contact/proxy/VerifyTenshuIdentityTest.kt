package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.getContactSas
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.contact.verifyTenshuIdentity
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.util.deriveContactSas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Re-verification through a real client (`contacts-app.md` D4/Q2a).
 *
 * The assertions here are the trust rules, not the plumbing: verification is only ever the
 * record of a human verdict, it moves in both directions, it cannot touch `ATTESTED`, and
 * recording it must not destroy the rest of the key's row.
 */
class VerifyTenshuIdentityTest {

    private fun serviceFor(harness: ContactTestHarness): ContactService {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)
    }

    private fun <T : org.tenshu.core.Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    private fun harness() =
        createContactProxy(FakeInviteRelay(), identityPhrase = "alice phrase")

    private val peerKey = ByteArray(32) { (it * 7).toByte() }

    private fun seedIdentity(
        harness: ContactTestHarness,
        fingerprint: String = "peer-key",
        verification: String = TenshuIdentity.Verification.UNVERIFIED,
        grantSeed: ByteArray = ByteArray(16) { 9 },
    ) {
        harness.proxy.executionContext.saveData(
            TenshuIdentity.create(
                context = harness.proxy.tContext,
                fingerprint = fingerprint,
                publicKey = peerKey,
                personId = "person-1",
                selfAssertedName = "Mira",
                verification = verification,
                verifiedAt = 0L,
                source = TenshuIdentity.Source.INVITE_LINK,
                grantSeed = grantSeed,
            )
        )
    }

    // ── the comparison code ──────────────────────────────────────────────────

    @Test
    fun theSasMatchesTheSharedDerivation() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness)

        val sas = service.getContactSas("peer-key").expect("getContactSas").get()

        // The same six digits the peer derives on their side — the derivation is over both
        // public keys and is symmetric, which is the whole reason re-verification needs no
        // exchange.
        val ownKey = assertNotNull(harness.components.identityKeyPair()).publicKey
        assertEquals(deriveContactSas(ownKey, peerKey), sas)
        assertEquals(6, sas.length)
    }

    @Test
    fun anUnknownKeyYieldsNoCodeRatherThanAnError() = runTest {
        // The screen renders the absence; it is not an error to report.
        val service = serviceFor(harness())
        assertEquals("", service.getContactSas("nobody").expect("getContactSas").get())
    }

    // ── recording the verdict ────────────────────────────────────────────────

    @Test
    fun confirmingElevatesTheKeyAndStampsIt() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness)

        service.verifyTenshuIdentity("peer-key", verified = true).expect("verify")

        val row = assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("peer-key"))
        assertEquals(TenshuIdentity.Verification.VERIFIED, row.verification)
        assertEquals(TenshuIdentity.Source.QR_SAS, row.source)
    }

    @Test
    fun unVerifyingReturnsTheKeyToUnverified() = runTest {
        // Q2b requires the way back: a contact whose keyring went mixed needs to be able to
        // withdraw a verification, not only add one.
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness, verification = TenshuIdentity.Verification.VERIFIED)

        service.verifyTenshuIdentity("peer-key", verified = false).expect("verify")

        val row = assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("peer-key"))
        assertEquals(TenshuIdentity.Verification.UNVERIFIED, row.verification)
    }

    @Test
    fun attestedIsNeverTouchedInEitherDirection() = runTest {
        // ⚠ ATTESTED is Rei-carried and outranks a local human verdict. Letting this op
        // write over it would let a device-local action silently discard an attestation.
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness, verification = TenshuIdentity.Verification.ATTESTED)

        service.verifyTenshuIdentity("peer-key", verified = false).expect("verify")
        assertEquals(
            TenshuIdentity.Verification.ATTESTED,
            TenshuIdentityLocalView(harness).byFingerprint("peer-key")?.verification,
        )

        service.verifyTenshuIdentity("peer-key", verified = true).expect("verify")
        assertEquals(
            TenshuIdentity.Verification.ATTESTED,
            TenshuIdentityLocalView(harness).byFingerprint("peer-key")?.verification,
        )
    }

    @Test
    fun verifyingPreservesTheRestOfTheRow() = runTest {
        // ⚠⚠ The replace-write hazard (deletion by omission). Losing `grantSeed` here would silently
        // make the contact un-grantable; losing `publicKey` would make them unreachable.
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness)

        service.verifyTenshuIdentity("peer-key", verified = true).expect("verify")

        val row = assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("peer-key"))
        assertEquals("person-1", row.personId, "the contact link survives")
        assertEquals("Mira", row.selfAssertedName, "the quarantined claim survives")
        // The SAS still derives, which is only possible if the public key is intact.
        assertTrue(service.getContactSas("peer-key").expect("getContactSas").get().isNotEmpty())
    }

    @Test
    fun anUnknownKeyCannotBeVerified() = runTest {
        val service = serviceFor(harness())
        val result = service.verifyTenshuIdentity("nobody", verified = true)
        assertTrue(result is TResult.Failure<*>)
    }
}
