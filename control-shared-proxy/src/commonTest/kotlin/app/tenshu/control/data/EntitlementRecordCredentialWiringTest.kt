package app.tenshu.control.data

import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.identity.Entitlement
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.initEntitlementStorageReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The **wiring** half of the entitlement credential — the gap that let a real
 * on-device failure through (`docs/tech-debt.md` → "Entitlement enforcement is untested
 * end-to-end"). `EntitlementRecordCredentialTest` already pins the encoder and the
 * provider in isolation; what nothing covered is whether a saved [Entitlement] row
 * actually *reaches* the provider, which is what decides between a `200` and a
 * `402 entitlement_required` on every `POST /sync/push`.
 *
 * Observed 2026-07-26 on real hardware: a correctly-signed, correctly-owner-bound,
 * in-window entitlement sat in the desktop's store while every push was refused —
 * consistent with `proofHeader()` returning `null` because the provider was never fed.
 *
 * These tests drive the *production* seam ([initEntitlementStorageReader] with the same
 * `recordSink` lambda `initEntitlementStorage` installs) against a real
 * `ExecutionContext` + store, so a regression here fails locally instead of on a phone.
 */
class EntitlementRecordCredentialWiringTest {

    private val owner = "owner-under-test"
    private val now = 5_000L

    private fun entitlement(proxy: TestProxy, ownerId: String = owner): Entitlement =
        Entitlement.create(
            context = proxy.tContext,
            ownerId = ownerId,
            publisherId = "tenshu.commerce",
            productId = "tenshu.drive.free",
            validFrom = 1_000L,
            validUntil = 9_000L,
            publisherSignature = byteArrayOf(1, 2, 3, 4),
        )

    /** A proxy whose registry knows the identity schemas ([Entitlement] + its DataId trait). */
    private fun proxyOf(scope: kotlinx.coroutines.CoroutineScope): TestProxy =
        TestProxy(coroutineScope = scope).apply {
            schemaRegistry.registerConfig(identityServiceCommonModule)
        }

    /**
     * Installs the reader exactly as `initEntitlementStorage` does — the sink is the
     * same one-liner (`{ signed -> provider.update(signed) }`), so this exercises the
     * production path rather than a test-only shortcut.
     */
    private fun install(
        proxy: TestProxy,
        credential: EntitlementRecordCredentialProvider,
        ownerId: () -> String?,
    ) = proxy.executionContext.initEntitlementStorageReader(
        provider = EntitlementStorageProvider(),
        ownerId = ownerId,
        now = { now },
        recordSink = { signed -> credential.update(signed) },
    )

    @Test
    fun aRenewalNotifies_evenThoughARecordWasAlreadyHeld() {
        // ⚠⚠ The regression this guards is the exact state the 2026-08-04 incident left a device in
        // (`sync-device-catch-up.md` §1): an EXPIRED record is still a non-null `current`, so the
        // old `previous == null` edge stayed silent when the renewal finally arrived — and the 402
        // backoff was left to time out on its own despite the fix being in hand.
        //
        // Without this the C1 targeted fetch is only half a fix: it can land the row, but nothing
        // tells the scheduler to spend it.
        val credential = EntitlementRecordCredentialProvider()
        var notifications = 0
        credential.onRecordUsable = { notifications++ }

        val expired = signed(validFrom = 1_000L, validUntil = 2_000L)
        credential.update(expired)
        assertEquals(1, notifications, "absent -> present still notifies")

        val renewed = signed(validFrom = 2_000L, validUntil = 9_000L)
        credential.update(renewed)
        assertEquals(
            2, notifications,
            "a renewal landing over an expired record must notify — it is the whole recovery signal"
        )
    }

    @Test
    fun anIdenticalReSaveDoesNotReNotify() {
        // Keeps it edge-triggered. The reader mirrors EVERY save into the sink, so firing on each
        // one would wake + reset the backoff continuously on an idle device.
        val credential = EntitlementRecordCredentialProvider()
        var notifications = 0
        credential.onRecordUsable = { notifications++ }

        val record = signed(validFrom = 1_000L, validUntil = 9_000L)
        credential.update(record)
        credential.update(record)
        credential.update(signed(validFrom = 1_000L, validUntil = 9_000L))

        assertEquals(1, notifications, "only a strictly later window is a new credential")
    }

    private fun signed(validFrom: Long, validUntil: Long) =
        org.tenshu.identity.EntitlementSignature.SignedEntitlement(
            ownerId = owner,
            publisherId = "tenshu.commerce",
            productId = "tenshu.drive.free",
            validFrom = validFrom,
            validUntil = validUntil,
            signature = byteArrayOf(1, 2, 3, 4),
        )

    @Test
    fun savedEntitlement_reachesTheCredentialProvider() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        install(proxy, credential) { owner }

        // Nothing saved yet: no credential to present (the honest starting state).
        assertNull(credential.proofHeader(now), "no record yet ⇒ no proof")

        proxy.executionContext.saveData(entitlement(proxy))

        // THE production expectation: a stored, in-window, owner-matching entitlement is
        // presentable as `X-Entitlement-Proof`. If this is null, every push 402s.
        assertNotNull(
            credential.proofHeader(now),
            "a saved in-window entitlement for the current owner must be presentable as a " +
                "proof — a null here is exactly the on-device 402 (entitlement_required)",
        )
    }

    /**
     * `refreshFigures` resolves the entitlement **by `ownerId()`**, so while the realm is
     * locked/initialising (owner still `null`) the lookup finds nothing and no credential can
     * be derived. What matters is the *recovery*: once the owner resolves, a save must
     * populate the provider. (Note this is the "nothing to present yet" case — distinct from
     * the regression below, where a good credential must survive the same missed read.)
     */
    @Test
    fun ownerUnresolvedAtSave_yieldsNoCredential_thenRecoversOnceOwnerAppears() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        var currentOwner: String? = null
        install(proxy, credential) { currentOwner }

        // Saved while "locked": the row lands in the store, but the reader cannot resolve
        // an owner, so no credential is derived.
        proxy.executionContext.saveData(entitlement(proxy))
        assertNull(
            credential.proofHeader(now),
            "owner unresolved ⇒ the reader cannot find the row ⇒ no proof (expected)",
        )

        // Unlock: the owner appears. A subsequent save must re-derive and populate.
        currentOwner = owner
        proxy.executionContext.saveData(entitlement(proxy))
        assertNotNull(
            credential.proofHeader(now),
            "once the owner resolves, the stored entitlement must become presentable — " +
                "otherwise a device that persisted its entitlement while locked can never push",
        )
    }

    @Test
    fun entitlementForAnotherOwner_isNotPresentable() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        install(proxy, credential) { owner }

        proxy.executionContext.saveData(entitlement(proxy, ownerId = "somebody-else"))

        // The relay 402s a proof whose owner != the realm owner, so presenting one is
        // strictly worse than presenting none.
        assertNull(
            credential.proofHeader(now),
            "another owner's entitlement must never be presented as this owner's proof",
        )
    }

    /**
     * The **locked-start recovery guarantee**, and the one property nothing asserted before: an
     * entitlement **already persisted by a previous session** must become presentable once the
     * owner resolves, *without* waiting for a fresh entitlement save.
     *
     * This is the shape of the production incident (2026-07-26): the record was in the store and
     * valid, yet every `POST /sync/push` was refused for ~15 minutes. An entitlement is minted
     * **once**, so if the only recovery path were "the next entitlement save", a device that
     * persisted its record while locked would stay credential-less for the whole session — which
     * is exactly what the earlier test in this file shows happens without a backstop.
     *
     * `initEntitlementStorageReader`'s `scope` poll is that backstop (it re-derives when
     * `ownerId()` stops matching the owner the figures were last derived for). Here we install the
     * reader against a null owner, flip the owner *without touching the store*, and require the
     * credential to appear on its own.
     */
    @Test
    fun persistedEntitlement_becomesPresentableAfterUnlock_withoutAnotherSave() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        var currentOwner: String? = null

        // A record persisted by an EARLIER session: already in the store before the reader is
        // installed, so no save will ever fire for it again.
        proxy.executionContext.saveData(entitlement(proxy))

        proxy.executionContext.initEntitlementStorageReader(
            provider = EntitlementStorageProvider(),
            ownerId = { currentOwner },
            now = { now },
            recordSink = { signed -> credential.update(signed) },
            // The backstop under test. runTest's virtual clock drives its delay loop.
            scope = backgroundScope,
        )

        assertNull(credential.proofHeader(now), "still locked ⇒ nothing derivable yet")

        // Unlock: the owner resolves. Nothing else happens — no save, no write, no user action.
        currentOwner = owner
        advanceTimeBy(3_000)

        assertNotNull(
            credential.proofHeader(now),
            "an entitlement persisted before unlock must become presentable on the owner " +
                "transition alone — an entitlement is minted once, so without this backstop a " +
                "locked-start device can never present a credential and every push 402s",
        )
    }

    /**
     * ⚠ **The regression test for the production 402** (`docs/tech-debt.md` → "Entitlement
     * enforcement is untested end-to-end"). A credential that is already good must **survive** a
     * refresh whose read resolves nothing.
     *
     * Before the fix, `refreshFigures` mirrored every read into the sink, so a transient null
     * owner — a lock/unlock, a re-derivation mid-initialisation — *wiped* a perfectly valid
     * credential, and every subsequent push was refused `402 entitlement_required` ("the client
     * attached nothing") until the next successful refresh. On a locked realm that state held
     * indefinitely. Crucially the failure was **intermittent within a live session**: pushes
     * succeeded, then stopped, which is what made it so hard to place from the outside.
     */
    @Test
    fun transientlyUnreadableOwner_doesNotWipeAnAlreadyValidCredential() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        var currentOwner: String? = owner
        install(proxy, credential) { currentOwner }

        proxy.executionContext.saveData(entitlement(proxy))
        assertNotNull(credential.proofHeader(now), "precondition: a good credential is held")

        // The realm locks (or the owner is momentarily unresolved) and something triggers a
        // refresh — here an unrelated save, exactly as an ordinary write would.
        currentOwner = null
        proxy.executionContext.saveData(entitlement(proxy, ownerId = "unrelated-owner"))

        assertNotNull(
            credential.proofHeader(now),
            "a read that resolves nothing must NOT clear a valid credential — the record is " +
                "signed, owner-bound and windowed, and the relay re-checks all three, so keeping " +
                "it is never a false grant; clearing it is what produced the on-device 402s",
        )
    }

    /**
     * The deliberate exception: a genuine *delete* means the entitlement is gone, not merely
     * unreadable, so the credential must stop being presented. Without this the fix above would
     * over-correct into "present a revoked entitlement forever".
     */
    @Test
    fun explicitEntitlementDelete_clearsTheCredential() = runTest {
        val proxy = proxyOf(this)
        val credential = EntitlementRecordCredentialProvider()
        install(proxy, credential) { owner }

        val row = entitlement(proxy)
        proxy.executionContext.saveData(row)
        assertNotNull(credential.proofHeader(now), "precondition: a good credential is held")

        proxy.executionContext.deleteData(
            org.tenshu.core.entity.DataId.StringDataId.create(
                context = proxy.tContext,
                type = Entitlement.ID,
                keyValue = owner,
            )
        )

        assertNull(
            credential.proofHeader(now),
            "an explicit delete is the one miss that MUST clear the credential",
        )
    }
}
