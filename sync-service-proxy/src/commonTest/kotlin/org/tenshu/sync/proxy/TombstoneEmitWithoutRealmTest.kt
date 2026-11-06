package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Emitting a tombstone with **no realm** is a silent no-op, on every single-target form.
 *
 * ⚠⚠ Why this needs pinning. The id form exists so the emitter derives the keyed wire name
 * (EI-1), and it does that through `WireIdResolver.of`, which **throws** rather than returning
 * null — deliberately, because naming a tombstone locally diverges silently and a missing realm on
 * an emit path is a real defect.
 *
 * ⚠ But "sync is off" is not that defect. The hash form has always no-opped there, and callers
 * depend on it: `DataIdMigrator.retire` emits *after* writing its journal marker and deleting the
 * old row, so a throw would wedge the migration in precisely the state the marker exists to
 * recover, and `DeleteWorkout`/the retention trims would fail an ordinary local delete. So the
 * enabled check must come **before** the resolve — which is exactly what an eager
 * `wireIds.of(...)` in the argument list got wrong.
 */
class TombstoneEmitWithoutRealmTest {

    @Test
    fun theIdForm_isANoOpWithoutARealm_ratherThanAThrow() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        // ⚠ Deliberately NOT `initialize(...)`: this is the pre-setup / locked-realm state.
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        val observer = SyncObserver(proxy.executionContext, syncManager, crypto)

        val target = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "/a.txt",
        )

        assertNull(
            observer.emit(targetEntityId = target, targetEntityType = SyntheticTestData.ID),
            "no realm ⇒ nothing to emit; resolving the wire name eagerly would throw here instead",
        )
        assertNull(
            observer.emit(targetEntityIdHash = "12345", targetEntityType = SyntheticTestData.ID),
            "the hash form's long-standing contract, which the id form must match",
        )
    }

    /**
     * ⚠⚠ The cascade form is the one `DeleteFile` calls, and it calls it **after** the rows and
     * their bytes are already gone. A throw there cannot protect anything — it only turns a delete
     * that happened into a reported failure, on every device that belongs to no realm.
     */
    @Test
    fun theCascadeForm_isANoOpWithoutARealm_ratherThanAThrow() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        // ⚠ Deliberately NOT `initialize(...)`, as above.
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        val observer = SyncObserver(proxy.executionContext, syncManager, crypto)

        fun entityAt(path: String) = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = path,
        )

        assertNull(
            observer.emitWithCascade(
                targetEntityId = entityAt("/f"),
                targetEntityType = SyntheticTestData.ID,
                cascadedEntityIds = listOf(entityAt("/f/a.txt")),
            ),
            "no realm ⇒ nothing to emit; the caller has already deleted, so a throw fails a delete that happened",
        )
    }

    /**
     * ⚠⚠ The other half of the cascade form's contract: a **member** whose realm cannot be named
     * right now (locked, or inside the cold start's key-load window) still owes its peers the
     * tombstone. Answering null there would report a delete as done while peers keep the file and
     * later resurrect it, so it stays a failure.
     */
    @Test
    fun theCascadeForm_stillRefuses_whenTheRealmIsMerelyLocked() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")
        val lockedButStillAMember = object : SyncManager by syncManager {
            override fun isEnabled(): Boolean = false
            override fun currentRealmContext(): RealmContext? = null
            override val residentOwnerId: String? get() = "owner-1"
        }

        val observer = SyncObserver(proxy.executionContext, lockedButStillAMember, crypto)
        val target = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "/f",
        )

        assertFailsWith<IllegalStateException>(
            "a locked member's delete must not read as a success with no tombstone queued",
        ) {
            observer.emitWithCascade(
                targetEntityId = target,
                targetEntityType = SyntheticTestData.ID,
                cascadedEntityIds = emptyList(),
            )
        }
    }

    /**
     * ⚠⚠ A **locked** realm is not "sync is off". `DeleteRealmDataImplementation` paces its reset in
     * chunks, so `LockRealm` can land between two of them — and the device still belongs to a realm
     * whose peers hold those rows. Answering `emptyList()` there lets the caller delete the chunk
     * with no tombstone queued, and once the local ids are gone nothing can ever name them again.
     * ⚠ The discriminator is `residentOwnerId` (durable membership), not `isEnabled()` (keys loaded
     * in this session) — the same distinction the cold-start key-load window needs.
     */
    @Test
    fun theBatchForm_answersNull_whenTheRealmIsMerelyLocked() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")
        val lockedButStillAMember = object : SyncManager by syncManager {
            override fun isEnabled(): Boolean = false
            override fun currentRealmContext(): RealmContext? = null
            override val residentOwnerId: String? get() = "owner-1"
        }

        val observer = SyncObserver(proxy.executionContext, lockedButStillAMember, crypto)
        val target = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "/a.txt",
        )

        assertNull(
            observer.emitAll(
                listOf(TombstoneEmitter.Target(entityId = target, entityType = SyntheticTestData.ID))
            ),
            "a locked realm still owes its peers tombstones — the caller must not delete the chunk",
        )
    }

    /**
     * ⚠⚠ The batch form's third answer. `emitAll` distinguishes `emptyList()` ("sync is off, so
     * deleting locally is correct") from `null` ("I could not name these — do not delete"), and
     * `DeleteRealmDataImplementation` acts on that difference by dropping the whole chunk. A realm
     * that disappears *between* the `isEnabled()` check and the owner read is the second case, not
     * the first: `SyncManagerImpl.lock()` clears `derivedKeys` and `currentOwnerId` separately, so
     * enabled-but-ownerless is precisely the mid-lock window — and classifying it as "sync is off"
     * deletes rows whose tombstone never went out.
     */
    @Test
    fun theBatchForm_answersNull_whenTheRealmVanishesAfterTheEnabledCheck() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")
        // The mid-lock window: keys still loaded (so `isEnabled()` passes) but the owner already
        // cleared. ⚠ `SyncManagerImpl` keeps `currentOwnerId` private, and `lock()` clears the keys
        // *first*, so the window cannot be reached by driving the real one — it only exists between
        // two of `lock()`'s own assignments. ⚠⚠ `currentRealmContext()` is an interface default, and
        // `by` delegation forwards defaults to the delegate, so overriding only the property would
        // leave the realm intact; it has to be overridden itself.
        val racingLock = object : SyncManager by syncManager {
            override val currentOwnerId: String? get() = null
            override fun currentRealmContext(): RealmContext? = null
        }
        assertTrue(racingLock.isEnabled(), "precondition: the enabled check still passes")

        val observer = SyncObserver(proxy.executionContext, racingLock, crypto)
        val target = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "/a.txt",
        )

        assertNull(
            observer.emitAll(
                listOf(
                    TombstoneEmitter.Target(
                        entityId = target,
                        entityType = SyntheticTestData.ID,
                    )
                )
            ),
            "an empty answer here would let the caller delete the chunk with no tombstone queued",
        )
    }
}
