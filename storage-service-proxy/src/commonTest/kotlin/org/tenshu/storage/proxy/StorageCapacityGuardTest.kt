package org.tenshu.storage.proxy

import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.NoStorageError
import org.tenshu.storage.test.FakeStorageProvider
import org.tenshu.storage.test.createStorageProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tier-2 guard (`docs/proposals/storage-capacity-gating.md` §2): [ensureCapacity]
 * throws the typed errors when the action won't fit, and is a no-op when storage
 * isn't wired/known (hard limit; unknown ⇒ allow).
 */
class StorageCapacityGuardTest {

    private fun executionContext(): ExecutionContext =
        createStorageProxy(FakeStorageProvider()).executionContext

    @Test
    fun nullHolder_isNoOp() {
        (null as StorageCapacityHolder?).ensureCapacity(executionContext(), requiredBytes = 100L)
    }

    @Test
    fun unknownFigures_isNoOp() {
        StorageCapacityHolder().ensureCapacity(executionContext(), requiredBytes = 100L)
    }

    @Test
    fun noStorage_throwsNoStorageError() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
        }
        val ex = assertFailsWith<TenshuException> {
            holder.ensureCapacity(executionContext(), requiredBytes = 0L)
        }
        assertTrue(ex.error is NoStorageError)
    }

    @Test
    fun overQuota_throwsInsufficientStorageError_withFigures() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 900L, limitBytes = 1_000L)
        }
        val ex = assertFailsWith<TenshuException> {
            holder.ensureCapacity(executionContext(), requiredBytes = 200L)
        }
        val error = ex.error
        assertTrue(error is InsufficientStorageError)
        assertEquals(200L, (error as InsufficientStorageError).requiredBytes.get())
        assertEquals(100L, error.availableBytes.get(), "available = limit - used")
    }

    @Test
    fun fits_allows() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L)
        }
        holder.ensureCapacity(executionContext(), requiredBytes = 100L)
    }

    @Test
    fun exactlyAtLimit_allows_butOneOver_throws() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 0L, limitBytes = 1_000L)
        }
        holder.ensureCapacity(executionContext(), requiredBytes = 1_000L) // used+req == limit ⇒ ok
        assertFailsWith<TenshuException> {
            holder.ensureCapacity(executionContext(), requiredBytes = 1_001L)
        }
    }

    // --- ensureWithinQuota: the local-first UPLOAD gate (ADR 0019) ---

    @Test
    fun withinQuota_noSubscription_isAllowed_localFirst() {
        // The key local-first difference vs ensureCapacity: no subscription must NOT
        // block an upload (it's stored locally + synced when a subscription binds).
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 5_000L)
    }

    @Test
    fun withinQuota_unknownOrNull_isNoOp() {
        (null as StorageCapacityHolder?).ensureWithinQuota(executionContext(), requiredBytes = 100L)
        StorageCapacityHolder().ensureWithinQuota(executionContext(), requiredBytes = 100L)
    }

    @Test
    fun withinQuota_subscribedOverQuota_throwsInsufficientStorageError() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 900L, limitBytes = 1_000L)
        }
        val ex = assertFailsWith<TenshuException> {
            holder.ensureWithinQuota(executionContext(), requiredBytes = 200L)
        }
        val error = ex.error
        assertTrue(error is InsufficientStorageError)
        assertEquals(200L, (error as InsufficientStorageError).requiredBytes.get())
        assertEquals(100L, error.availableBytes.get())
    }

    @Test
    fun withinQuota_subscribedFits_allows() {
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L)
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 100L)
    }

    @Test
    fun withinQuota_relayAlreadyRefusedForSpace_throwsThoughTheArithmeticFits() {
        // ⚠⚠ The parallel-device case. These figures re-fetch on a 60 s backstop, so a
        // second device of the same owner can fill the realm without this one noticing — the
        // arithmetic below says a 100-byte file fits into 990 free bytes, and the relay has
        // already said otherwise. Refusing here is what turns "accepted, journalled, then silently
        // never uploaded" into a dialog at the moment the user picks the file.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L)
            relayRejected = true
        }
        val ex = assertFailsWith<TenshuException> {
            holder.ensureWithinQuota(executionContext(), requiredBytes = 100L)
        }
        assertTrue(ex.error is InsufficientStorageError)
    }

    @Test
    fun withinQuota_countsBytesAlreadyQueuedToUpload_notJustWhatTheRelayHolds() {
        // ⚠⚠ The queue counts as spent: 31.8 MB journalled and waiting, 836 bytes actually free.
        // `used + required` alone says a new file fits, so it would be accepted, journalled, and
        // join a queue that can never drain — a stuck upload rather than a refusal.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 200L, limitBytes = 1_000L)
            queuedContentBytes = 600L
        }
        val ex = assertFailsWith<TenshuException> {
            // 800 bytes of raw headroom would take this; 200 bytes of *real* headroom will not.
            holder.ensureWithinQuota(executionContext(), requiredBytes = 500L)
        }
        val error = ex.error
        assertTrue(error is InsufficientStorageError)
        assertEquals(
            200L,
            (error as InsufficientStorageError).availableBytes.get(),
            "the reported headroom must be net of the queue, not the raw figure",
        )
    }

    @Test
    fun withinQuota_aQueueThatStillLeavesRoom_doesNotRefuse() {
        // ⚠ The negative that keeps this from becoming "refuse whenever anything is queued". In
        // normal operation the journal is transiently non-empty on every single upload, so a gate
        // that keyed on the queue's *existence* would refuse everything.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 200L, limitBytes = 1_000L)
            queuedContentBytes = 100L
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 500L)
    }

    @Test
    fun withinQuota_queuedBytesAreIgnoredWithoutASubscription_localFirstIsUnchanged() {
        // Same ordering guarantee as the relay-refusal case: an unsubscribed device stores locally
        // and syncs later, and has no quota to be over.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
            queuedContentBytes = 10_000L
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 5_000L)
    }

    @Test
    fun withinQuota_relayRefusalIsIgnoredWithoutASubscription_localFirstIsUnchanged() {
        // ⚠ Ordering matters: the local-first escape must still come first. An unsubscribed device
        // stores locally and syncs later, and it has no quota to be over in the first place — a
        // stale rejection flag must not start blocking its uploads.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
            relayRejected = true
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 5_000L)
    }

    @Test
    fun releasedQueuedBytes_neverFreeMoreThanTheQueueHolds() {
        // The queue figure is per tick and may not include the entry yet: crediting more than it holds
        // would hand out room the relay never had.
        val holder = StorageCapacityHolder().apply {
            figures = StorageFigures(hasStorage = true, usedBytes = 900L, limitBytes = 1_000L)
            queuedContentBytes = 10L
        }
        holder.ensureWithinQuota(executionContext(), requiredBytes = 100L, releasedQueuedBytes = 10L)
        val ex = assertFailsWith<TenshuException> {
            holder.ensureWithinQuota(executionContext(), requiredBytes = 101L, releasedQueuedBytes = 500L)
        }
        assertEquals(100L, ((ex.error as InsufficientStorageError).availableBytes.get()), "limit - used, and no further")
    }
}
