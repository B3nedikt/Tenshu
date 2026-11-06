package org.tenshu.storage.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared decision behind every app's storage banner. The states are two different mechanisms
 * (see [RealmStorageState]), so each is pinned separately rather than as degrees of one scale.
 */
class RealmStorageStateTest {

    @Test
    fun noSubscription_isNoPlan_notFull() {
        // ⚠ The state that matters most and is silent today: `StorageSyncGate` keys on `hasStorage`,
        // so this shuts the gate and NOTHING pushes — from any app. Measured on a real realm:
        // 0 blocks on the relay with two devices correctly paired (§3.5).
        assertEquals(
            RealmStorageState.NoPlan,
            evaluateRealmStorage(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
    }

    @Test
    fun noSubscription_staysNoPlan_evenWithBytesRecorded() {
        // ⚠⚠ An unfunded realm can still report usage. Reading "used > limit" first would call it
        // Full and send the user to free up space, when the actual fix is to bind a plan at all —
        // different sentence, different outcome.
        assertEquals(
            RealmStorageState.NoPlan,
            evaluateRealmStorage(hasStorage = false, usedBytes = 5_000L, limitBytes = 0L),
        )
    }

    @Test
    fun overQuota_isFull_withTheFigures() {
        // The measured case: 10.88 GiB against a 5 GiB free plan.
        assertEquals(
            RealmStorageState.Full(usedBytes = 11_684_489_630L, limitBytes = 5_368_709_120L),
            evaluateRealmStorage(
                hasStorage = true,
                usedBytes = 11_684_489_630L,
                limitBytes = 5_368_709_120L,
            ),
        )
    }

    @Test
    fun exactlyAtTheLimit_isFull() {
        // `used >= limit`, matching `StorageStatusModel.isFull` (`remainingBytes <= 0`). At exactly
        // the limit the next byte cannot land, so saying nothing would be a lie by one byte.
        assertEquals(
            RealmStorageState.Full(usedBytes = 1_000L, limitBytes = 1_000L),
            evaluateRealmStorage(hasStorage = true, usedBytes = 1_000L, limitBytes = 1_000L),
        )
    }

    @Test
    fun roomLeft_isOk() {
        assertEquals(
            RealmStorageState.Ok,
            evaluateRealmStorage(hasStorage = true, usedBytes = 999L, limitBytes = 1_000L),
        )
    }

    @Test
    fun unknownFigures_isOk_soNoBannerFlashesAtStartup() {
        // ⚠⚠ Optimistic on unknown, the INVERSE of `StorageSyncGate`, which fails closed so a
        // starting device does not spam the relay. Both are right: withholding traffic and
        // accusing the user of a problem are not the same act, and a banner that flashes during
        // startup teaches the user to ignore it.
        assertEquals(
            RealmStorageState.Ok,
            evaluateRealmStorage(hasStorage = null, usedBytes = 5_000L, limitBytes = 1_000L),
        )
    }
}
