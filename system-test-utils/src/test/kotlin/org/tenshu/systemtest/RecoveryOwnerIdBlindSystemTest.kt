package org.tenshu.systemtest

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.client.syncService
import org.tenshu.sync.recoveryClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `relay-metadata-minimisation.md` F8, over the **real relay app** and the clients' **real
 * `RestSyncRelay`**.
 *
 * ⚠⚠ **Why it matters.** `ownerId = sha256(masterPub)` is public: the pairing QR is
 * `tenshu://join?o=<ownerId>&…` (`PairingUri.kt`). Before F8 anyone who saw one QR could ask the
 * relay, unauthenticated, for the realm's A9 recovery blob (`/recovery-blob?ownerId=`), whether the
 * realm exists (`/recovery-challenge?ownerId=`, and the claim's 404-vs-401) and how many devices it
 * has removed (`/realm-epoch?ownerId=`). F8 removed the blob routes outright (no client used them)
 * and made the other two blind. This class pins both halves: a stranger with the ownerId learns
 * nothing, and the owner's own phrase-only recovery still works end to end.
 */
class RecoveryOwnerIdBlindSystemTest {

    private suspend fun HttpResponse.statusAndBody() = status to bodyAsText()

    @Test
    fun aStrangerHoldingTheOwnerId_learnsNothing_fromTheRecoveryOrEpochRoutes(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            assertIs<TResult.Success<*>>(host.bootstrapAsHost(deviceName = "Host Phone"))
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)
            // An ownerId-shaped value nobody owns: the control every probe must be indistinguishable from.
            val nobody = "0".repeat(64)
            val relay = system.relayHttpClient

            // 1. The A9 blob route is gone: the realm's ownerId reaches nothing a stranger's guess doesn't.
            assertEquals(
                relay.get("/sync/recovery-blob?ownerId=$nobody").status,
                relay.get("/sync/recovery-blob?ownerId=$ownerId").status,
            )
            assertEquals(HttpStatusCode.NotFound, relay.get("/sync/recovery-blob?ownerId=$ownerId").status)

            // 2. The challenge: issued to anyone, naming no realm — so the same answer either way.
            val forRealm = relay.get("/sync/recovery-challenge?ownerId=$ownerId")
            val forNobody = relay.get("/sync/recovery-challenge?ownerId=$nobody")
            assertEquals(HttpStatusCode.OK, forRealm.status)
            assertEquals(forRealm.status, forNobody.status)

            // 3. The realm epoch: needs a device JWT; the ownerId parameter opens nothing.
            val epochForRealm = relay.get("/sync/realm-epoch?ownerId=$ownerId").statusAndBody()
            assertEquals(HttpStatusCode.Unauthorized, epochForRealm.first)
            assertEquals(epochForRealm, relay.get("/sync/realm-epoch?ownerId=$nobody").statusAndBody())
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⭐ The legitimate path, phrase only: a fresh device re-joins by recovery-claim — challenge
     * (no owner) → claim (master public key, verified before the realm lookup) → realm epoch with
     * its new device JWT — and lands in the right realm. No server-side blob is involved: A1/A13
     * derive from the phrase.
     */
    @Test
    fun thePhraseAlone_recoversTheRealm(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val recovered = system.client("recovered", TestClientRole.JoiningDevice)
            val phrase = Bip39.generateMnemonic()
            assertIs<TResult.Success<*>>(host.bootstrapAsHost(deviceName = "Host Phone", phrase = phrase))
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)

            val claim = recovered.tenshuClient.syncService.recoveryClaim(phrase, "Recovered Laptop")
            assertIs<TResult.Success<*>>(claim, "recovery must still work end to end: $claim")
            assertEquals(ownerId, recovered.syncManager.currentOwnerId)
            assertTrue(recovered.syncManager.currentDeviceId in system.knownDevices.deviceIds(ownerId))
        } finally {
            system.shutdown()
        }
    }

    /**
     * A phrase with no realm behind it gets a failure, not an admission — and leaves nothing
     * behind: no realm on the relay, and no identity seated on the device.
     *
     * ⚠⚠ The second half is the regression. `RecoveryClaim` used to seat the phrase
     * (`syncManager.initialize`) *before* claiming, and the device's own scheduler then ran the
     * lazy genesis for it on its next tick — so a valid-but-wrong phrase created a fresh empty
     * realm. Whether the claim saw that realm was a race (this test failed ~2 runs in 3 on
     * Success); that the realm got created was not. [SyncScheduler.drainNow] stands on a tick
     * boundary, so the realm assertion below is deterministic rather than racy.
     */
    @Test
    fun aPhraseWithNoRealm_isRefused_andCreatesNoRealm(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val stranger = system.client("stranger", TestClientRole.JoiningDevice)
            val phrase = Bip39.generateMnemonic()
            val claim = stranger.tenshuClient.syncService.recoveryClaim(phrase, "Lost Laptop")
            assertIs<TResult.Failure<*>>(claim, "a phrase with no realm must be refused: $claim")

            assertFalse(stranger.syncManager.isEnabled(), "a refused claim must not seat the phrase")
            assertNull(stranger.syncManager.currentOwnerId)
            stranger.syncScheduler.drainNow()
            val ownerId = ownerIdForMasterPublicKey(createDefaultSyncCryptoManager().deriveMasterKeyPair(phrase).publicKey)
            assertNull(system.realmStore.get(ownerId), "a refused claim must not leave a realm on the relay")
        } finally {
            system.shutdown()
        }
    }

    /**
     * T-RC-09's residual, closed end to end: a device already set up on its own realm that is
     * handed ANOTHER realm's phrase is refused, typed, before the relay hears of it — it neither
     * joins the other realm nor loses its own. The user is told to forget this device first.
     */
    @Test
    fun aDeviceAlreadySetUp_isRefused_recoveringAnotherRealm(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val phrase = Bip39.generateMnemonic()
            assertIs<TResult.Success<*>>(host.bootstrapAsHost(deviceName = "Host Phone", phrase = phrase))
            val hostRealm = assertNotNull(host.syncManager.currentOwnerId)

            val other = system.client("other", TestClientRole.RealmHost)
            assertIs<TResult.Success<*>>(other.bootstrapAsHost(deviceName = "Other Laptop"))
            val otherRealm = assertNotNull(other.syncManager.currentOwnerId)

            val claim = other.tenshuClient.syncService.recoveryClaim(phrase, "Other Laptop")

            val failure = assertIs<TResult.Failure<*>>(claim, "an already set-up device must be refused: $claim")
            assertIs<DeviceAlreadySetUpError>(failure.error)
            assertEquals(otherRealm, other.syncManager.currentOwnerId, "it keeps the realm it had")
            assertFalse(
                other.syncManager.currentDeviceId in system.knownDevices.deviceIds(hostRealm),
                "and the relay never admitted it to the other realm",
            )
        } finally {
            system.shutdown()
        }
    }
}
