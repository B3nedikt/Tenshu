package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import kotlin.test.Test
import kotlin.test.assertEquals

// The flat projection Control-on-iOS's read-only ServicesView binds
// (ios-services-status-and-purchase.md S1).
//
// ⚠ CE-4 changed what these assert: the FIGURES now always come from the synced status, never
// from the local account (which carried hardcoded literals — the same bug as the Compose card,
// inherited here). `sourceKey` still distinguishes a local account from a synced-only device,
// because that is about which ACTIONS are offered (D11), not about the number.
class ServicesStatusSnapshotTest {

    private fun account(
        plan: PublisherAccount.Plan = PublisherAccount.Plan.Free,
        identity: PublisherAccount.IdentityMethod =
            PublisherAccount.IdentityMethod.Email("b@example.com"),
    ) = PublisherAccount(
        plan = plan,
        identityMethod = identity,
        displayName = null,
    )

    @Test
    fun localAccountSetsTheSource_butTheFiguresComeFromTheSyncedStatus() {
        val snap = servicesStatusSnapshot(
            account = account(),
            status = StorageStatusModel(
                hasStorage = true,
                usedBytes = 1_000_000L,
                limitBytes = CommerceProducts.PREMIUM_LIMIT_BYTES,
            ),
        )
        assertEquals("premium", snap.planKey)
        assertEquals("localAccount", snap.sourceKey)
        assertEquals("b@example.com", snap.accountEmail)
        assertEquals(1_000_000L, snap.usedBytes)
        assertEquals(CommerceProducts.PREMIUM_LIMIT_BYTES, snap.limitBytes)
    }

    @Test
    fun signingInDoesNotChangeTheFigures() {
        // The iOS twin of the Compose regression: same status, same numbers, session or not.
        val status = StorageStatusModel(
            hasStorage = true,
            usedBytes = 7L,
            limitBytes = CommerceProducts.BASIC_LIMIT_BYTES,
        )
        val signedOut = servicesStatusSnapshot(account = null, status = status)
        val signedIn = servicesStatusSnapshot(account = account(), status = status)

        assertEquals(signedOut.planKey, signedIn.planKey)
        assertEquals(signedOut.usedBytes, signedIn.usedBytes)
        assertEquals(signedOut.limitBytes, signedIn.limitBytes)
    }

    @Test
    fun aRedeemedAccountReportsItsRealTier_notRei() {
        // ⚠ Deliberate change: "Rei" was never a plan — it is how the account was identified, and
        // the identity row is where that belongs. The tier is whatever the entitlement grants.
        val snap = servicesStatusSnapshot(
            account = account(
                plan = PublisherAccount.Plan.Rei,
                identity = PublisherAccount.IdentityMethod.ReiKey(source = "code"),
            ),
            status = StorageStatusModel(
                hasStorage = true,
                usedBytes = 0L,
                limitBytes = CommerceProducts.BASIC_LIMIT_BYTES,
            ),
        )
        assertEquals("basic", snap.planKey)
        assertEquals("localAccount", snap.sourceKey)
        assertEquals(null, snap.accountEmail)
    }

    @Test
    fun signedInBeforeTheEntitlementSyncs_saysNoPlanRatherThanGuessing() {
        // ⚠⚠ Load-bearing for iOS since 2026-08-26: `ServicesView.canSignIn` gates on
        // `planKey == "none"` precisely because this pairing exists — "signed in" and "funded" are
        // independent, and the window this test names is not the only way to reach it (a commerce
        // reset or a realm the account is no longer bound to hold it open indefinitely). Gating on
        // `sourceKey` instead hid iOS's only affordance in exactly that state.
        val snap = servicesStatusSnapshot(account = account(), status = null)
        assertEquals("none", snap.planKey)
        assertEquals("localAccount", snap.sourceKey)
        assertEquals(0L, snap.limitBytes)
        assertEquals("b@example.com", snap.accountEmail)
    }

    @Test
    fun noLocalAccount_fundedStatus_isSyncedRealmWithLimitDerivedTier() {
        val snap = servicesStatusSnapshot(
            account = null,
            status = StorageStatusModel(
                hasStorage = true,
                usedBytes = 42L,
                limitBytes = CommerceProducts.BASIC_LIMIT_BYTES,
            ),
        )
        assertEquals("basic", snap.planKey)
        assertEquals("syncedRealm", snap.sourceKey)
        assertEquals(42L, snap.usedBytes)
        assertEquals(CommerceProducts.BASIC_LIMIT_BYTES, snap.limitBytes)
        assertEquals(null, snap.accountEmail)
    }

    @Test
    fun unrecognisedLimitFallsBackToFreeTier() {
        val snap = servicesStatusSnapshot(
            account = null,
            status = StorageStatusModel(hasStorage = true, usedBytes = 0, limitBytes = 12345L),
        )
        assertEquals("free", snap.planKey)
        assertEquals("syncedRealm", snap.sourceKey)
    }

    @Test
    fun emptyFloorSaysNoPlan_notFree() {
        // "Free" is a real (signed-up, 5 GiB) tier; the unfunded floor is not it.
        assertEquals("none", ServicesStatusSnapshot.EMPTY.planKey)
    }

    @Test
    fun nothingFunded_isTheEmptyFloor() {
        assertEquals(
            ServicesStatusSnapshot.EMPTY,
            servicesStatusSnapshot(
                account = null,
                status = StorageStatusModel(hasStorage = false, usedBytes = 0, limitBytes = 0),
            ),
        )
        assertEquals(ServicesStatusSnapshot.EMPTY, servicesStatusSnapshot(null, null))
    }
}
