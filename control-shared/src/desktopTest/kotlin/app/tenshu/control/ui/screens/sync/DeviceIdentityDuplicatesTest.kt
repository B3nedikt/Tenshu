package app.tenshu.control.ui.screens.sync

import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.schemaProvider
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [previousIdentitiesOfThisDevice] — spotting a roster row that is this device's own earlier
 * identity (`drive-upload-stop-and-reclamation.md` §4.23, D14).
 *
 * ⚠⚠ Why it earns tests of its own: a false positive here invites the user to revoke a live
 * machine, and `Device` rows have **no regeneration path** — the mistake cannot be undone. The
 * negatives below are therefore the load-bearing half.
 */
class DeviceIdentityDuplicatesTest {

    private lateinit var context: org.tenshu.core.core.TContext

    @BeforeTest
    fun setup() {
        context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
    }

    private fun device(id: String, key: ByteArray, name: String = "Desktop") = Device.create(
        context = context,
        deviceId = id,
        name = name,
        publicKey = key,
    )

    @Test
    fun findsAnEarlierIdentityOfThisDevice_sameKeyDifferentId() {
        // The measured case (2026-09-06): two rows named "Desktop (benedikt on Mac OS X)" sharing
        // one public key, one of them the id this Mac actually runs as. The other can never publish
        // an HLC tip, and the reclamation barrier waits for it for ever.
        val key = byteArrayOf(1, 2, 3)
        val devices = listOf(
            device("live-id", key),
            device("orphan-id", key),
            device("phone-id", byteArrayOf(9, 9, 9), name = "OnePlus"),
        )

        assertEquals(
            setOf("orphan-id"),
            previousIdentitiesOfThisDevice(devices, currentDeviceId = "live-id"),
        )
    }

    @Test
    fun neverFlagsTheCurrentDeviceItself() {
        // ⚠ It shares its own key by definition. Flagging it would offer to revoke the machine the
        // user is holding — which `SyncRepository` refuses anyway, so the row would be an
        // unactionable warning about nothing.
        val key = byteArrayOf(1, 2, 3)
        val devices = listOf(device("live-id", key), device("orphan-id", key))

        assertTrue("live-id" !in previousIdentitiesOfThisDevice(devices, "live-id"))
    }

    @Test
    fun flagsNothingWhenEveryDeviceHasItsOwnKey() {
        // The ordinary realm. Distinct machines have distinct keypairs, so a healthy roster must
        // produce no warnings at all.
        val devices = listOf(
            device("a", byteArrayOf(1)),
            device("b", byteArrayOf(2)),
            device("c", byteArrayOf(3)),
        )

        assertEquals(emptySet(), previousIdentitiesOfThisDevice(devices, "a"))
    }

    @Test
    fun saysNothingAboutTwoPeersSharingAKey_becauseThisReplicaCannotKnowWhichIsLive() {
        // ⚠⚠ Same shape, different claim. Two *other* devices sharing a key is a backup-restore
        // clone, and from here there is no way to tell which one is running. Calling either
        // "previous" would be a guess presented as a fact — and acting on it would revoke a live
        // peer, unrecoverably.
        val shared = byteArrayOf(7, 7)
        val devices = listOf(
            device("me", byteArrayOf(1)),
            device("peer-a", shared),
            device("peer-b", shared),
        )

        assertEquals(emptySet(), previousIdentitiesOfThisDevice(devices, "me"))
    }

    @Test
    fun saysNothingWhenTheCurrentDeviceIsNotInTheRoster() {
        // A degraded roster (the store lost this device's own row — `reconcileOwnDeviceRow`'s case).
        // With no key to compare against, every answer would be a guess.
        val devices = listOf(device("a", byteArrayOf(1)), device("b", byteArrayOf(1)))

        assertEquals(emptySet(), previousIdentitiesOfThisDevice(devices, "not-in-roster"))
    }

    @Test
    fun saysNothingWhenTheCurrentDeviceIdIsUnknown() {
        // The screen renders before `getDeviceId()` resolves; an empty id must not match rows.
        val devices = listOf(device("a", byteArrayOf(1)), device("b", byteArrayOf(1)))

        assertEquals(emptySet(), previousIdentitiesOfThisDevice(devices, ""))
    }

    @Test
    fun anEmptyKeyMatchesNothing_ratherThanEverything() {
        // ⚠⚠ The dangerous degenerate case: a row with no key. Comparing by equality would make
        // every other keyless row "an earlier identity of this device" and invite the user to
        // revoke the realm one confirmation at a time.
        val devices = listOf(
            device("me", byteArrayOf()),
            device("other", byteArrayOf()),
            device("third", byteArrayOf()),
        )

        assertEquals(emptySet(), previousIdentitiesOfThisDevice(devices, "me"))
    }
}
