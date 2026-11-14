package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.resolveListField
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Concurrent move vs delete**: A moves `/x→/y` while B deletes `/x`, neither having seen the
 * other. `file-content-addressing.md` §3.3.3, finding B, bullet **rename-vs-delete** says the move
 * wins and the file survives at `/y`:
 *
 * > both tombstone `/x` (idempotent, highest-HLC); `/y` has no competing tombstone ⇒ **move wins,
 * > file survives at `/y`**. B's delete of the *old* path does not reach the *new* path.
 * > Non-destructive.
 *
 * ⚠⚠ This is the composition the content plane's free protocol is designed around: the survivor at
 * `/y` references content whose last known holder was just deleted. A wrong assumption here means a
 * protocol built for a failure that does not happen — or one that does not cover the failure that
 * does.
 *
 * ## ⚠⚠ Why a system test, and why it is genuinely two-peer
 *
 * The claim is about **two independently-written histories merging**, so a single device cannot
 * express it: `MoveFile` and `DeleteFile` on one device are sequential, the second sees the first's
 * result, and there is no merge at all. A double for either half would have to model the tombstone
 * ordering under test.
 *
 * The divergence is produced by shutting both devices' **personal push gate** (`storageGate`) — the
 * production local-first gate, not a test seam. With it shut a device keeps writing locally and
 * queueing blocks and tombstones, and pushes none of them; it keeps *pulling*, which is harmless
 * while neither peer has pushed anything to pull. So each device edits the realm exactly as it last
 * saw it — the real precondition — and reopening the gates one at a time makes the **ingest order**
 * a parameter rather than a race. [assertNothingCrossed] pins that the partition actually held: if
 * either device's work had leaked to the relay during the window, the second edit would have been
 * made against the first's result and the test would be asserting a sequence, not a merge.
 *
 * ## Stepped: each ordering is written, not waited for
 *
 * The system is stepped (`TestSystem` KDoc, `docs/testing.md` §12): no device ticks unless the test
 * ticks it. Two consequences:
 *
 *  - A gate cannot change under a tick in flight, because no tick is in flight between the test's
 *    own steps. The polled version had to close both gates and then cross a `drainNow()` boundary
 *    on each device before writing — a gate is sampled once per tick, and a tick that sampled
 *    `true` an instant before the close would carry the edit straight to the relay. Here that
 *    hazard has no loop to come from.
 *  - The release is a sequence. With one gate open and the other shut, rounds of
 *    [TestSystem.syncUntil] over both devices push only the released device's edit and let the
 *    other *pull* it, so "the move reaches the relay first" is true by construction, and the
 *    second gate opens only once its arrival on the peer is observed.
 *
 * ## What is pinned
 *
 * Both ingest orderings, because order-independence is the whole claim:
 *  1. `/y` exists and `/x` is gone, **on both devices** — including on the deleter, which never
 *     issued a create.
 *  2. `/y` still carries the original `contentId`. That is what makes the reference live; a move
 *     that dropped it would leave the bytes unreachable *and* reclaimable while the row still
 *     listed.
 *  3. `/y` is a member of the target folder's `FILES` and is not reaped. `purgeDeadMembers` calls a
 *     member dead when it is tombstoned **and** absent locally (`SyncProcessor`), so an
 *     interleaving that made `/y` look briefly like both would silently strip it from the folder
 *     while the entity itself survived — invisible to assertion 1.
 *  4. The state **settles** there. Every assertion is made after [TestSystem.syncUntilQuiet] — two
 *     rounds on both devices in which nothing is pushed — rather than on the first round the
 *     convergence predicate holds, because the failure mode that matters (a later tick reaping
 *     `/y`, a late tombstone resurrecting `/x`) is one a first-sighting assertion cannot see.
 *
 * ## ⚠⚠ What is NOT pinned: the bytes
 *
 * Everything above is **metadata**. Whether the *bytes* survive depends on **how long a peer's
 * write stays unpublished**, not on the ingest order: content is freed only by the relay's own
 * sweep, a grace period T after its last edge goes, and a push that names freed content is revived
 * or retired (`content-plane-cr2-quorumless.md` §10.7). *"Has every peer read the log"* is not
 * *"has every peer published what it wrote"*.
 *
 * ⚠ The byte half is owed separately (§10.7): *a move pushed within T keeps its
 * content, one pushed after T gets `content_freed`*, in both orderings and with the bytes checked.
 * It is deliberately **not** asserted here in either direction: this test's timing decides the
 * byte outcome, so a byte assertion would pin the timing, not the protocol. This class is the
 * metadata half only.
 */
class ConcurrentMoveVsDeleteSystemTest {

    private companion object {
        const val SOURCE_FOLDER = "/src"
        const val TARGET_FOLDER = "/dst"
        const val OLD_PATH = "$SOURCE_FOLDER/x.bin"
        const val NEW_PATH = "$TARGET_FOLDER/y.bin"

    }

    @Test
    fun theMoveWinsWhenTheMoveReachesTheRelayFirst() = runBlocking {
        moveVersusDelete(moveReachesTheRelayFirst = true)
    }

    @Test
    fun theMoveWinsWhenTheDeleteReachesTheRelayFirst() = runBlocking {
        moveVersusDelete(moveReachesTheRelayFirst = false)
    }

    private suspend fun moveVersusDelete(moveReachesTheRelayFirst: Boolean) {
        val system = TestSystem.builder().stepped().build()
        try {
            // Both gates start open so the shared starting state can be established normally.
            val moverPushes = AtomicBoolean(true)
            val deleterPushes = AtomicBoolean(true)

            val mover = system.client(
                deviceId = "mover",
                role = TestClientRole.RealmHost,
                storageGate = SyncGate { moverPushes.get() },
            )
            val deleter = system.client(
                deviceId = "deleter",
                role = TestClientRole.JoiningDevice,
                storageGate = SyncGate { deleterPushes.get() },
            )
            system.pair(host = mover, joiner = deleter, hostName = "Mover Tablet", joinerName = "Deleter Phone")
            val ownerId = mover.syncManager.currentRealmContext()!!.ownerId

            // ---- Shared starting state: /src/x.bin exists on BOTH devices, /dst exists on both.
            val fs = mover.tenshuClient.fileService.defaultFileSystem
            assertTrue(fs.createFolder(SOURCE_FOLDER) is TResult.Success<*>, "createFolder $SOURCE_FOLDER")
            assertTrue(fs.createFolder(TARGET_FOLDER) is TResult.Success<*>, "createFolder $TARGET_FOLDER")
            val payload = ByteArray(4_000) { (it % 251).toByte() }
            assertTrue(
                fs.uploadFile(parentPath = SOURCE_FOLDER, fileName = "x.bin", data = payload)
                    is TResult.Success<*>,
                "uploadFile $OLD_PATH",
            )
            val contentId = assertNotNull(contentIdOf(mover, OLD_PATH), "the upload has a contentId")

            system.syncUntil("both devices hold $OLD_PATH and $TARGET_FOLDER, and the relay holds its content") {
                rowAt(deleter, OLD_PATH) != null &&
                    rowAt(deleter, TARGET_FOLDER) != null &&
                    system.contentRefs.refsOf(ownerId, contentId).isNotEmpty()
            }
            assertEquals(
                contentId,
                contentIdOf(deleter, OLD_PATH),
                "precondition: both devices agree on the file and its content before they diverge",
            )
            // ⚠ Not decoration. The end-state asserts that $SOURCE_FOLDER no longer lists
            // $OLD_PATH; that assertion is vacuous unless [childPathsOf] is shown to report the
            // membership while it IS there. Same reason the end-state's positive membership
            // assertion on $TARGET_FOLDER is what keeps this one honest.
            assertTrue(
                OLD_PATH in childPathsOf(deleter, SOURCE_FOLDER),
                "precondition: $SOURCE_FOLDER lists $OLD_PATH; got ${childPathsOf(deleter, SOURCE_FOLDER)}",
            )

            // ⚠⚠ Precondition for a pull-only tick: a device whose push gate is shut still pulls
            // only once `realmRelayRegistered` has latched (`SyncScheduler.drainAndPull`'s
            // `pullAllowed`), and a joiner latches it in `RealmRegistrar.ensureRegistered` on a
            // push-allowed tick AFTER its first pull. The polled version got that second open tick
            // from its cadence; stepped, the joiner can have pulled exactly once, and its gated
            // ticks below would then do nothing at all — so the ordering would degrade silently.
            system.syncUntil("both devices have latched their relay registration") {
                mover.syncManager.realmRelayRegistered && deleter.syncManager.realmRelayRegistered
            }

            // ---- The partition. Nothing either device does from here reaches the relay.
            //
            // In a stepped system no tick is running while these flags change, so every tick from
            // here samples them shut; the polled version's `drainNow()` boundary had nothing else
            // to do.
            val tombstonesBefore = tombstoneCount(system, ownerId)
            moverPushes.set(false)
            deleterPushes.set(false)

            // ---- The two concurrent edits, each made against the realm as its own device saw it.
            assertTrue(
                mover.tenshuClient.fileService.defaultFileSystem.moveFile(OLD_PATH, NEW_PATH)
                    is TResult.Success<*>,
                "the mover moves $OLD_PATH -> $NEW_PATH",
            )
            assertTrue(
                deleter.tenshuClient.fileService.defaultFileSystem.deleteFile(OLD_PATH)
                    is TResult.Success<*>,
                "the deleter deletes $OLD_PATH",
            )

            // ⚠⚠ The divergence is only real if neither edit crossed. Each device ticks with its gate
            // shut — a real push attempt and a real pull — and then it is checked from the relay's
            // own state and from the peer's.
            system.sync(mover, deleter)
            assertNothingCrossed(system, ownerId, tombstonesBefore, mover, deleter)

            // ---- Release, one device at a time, in the ordering under test.
            //
            // ⚠⚠ The ordering is ESTABLISHED, not assumed: the shut gate means only the released
            // device can push in these rounds, and each branch waits until the released
            // device's whole delete/move — blocks *and* its tombstone for $OLD_PATH — is on the
            // relay and has landed on the peer, before the second gate opens. Without the tombstone
            // half the wait can finish on the block drain alone (a tick pushes every block before
            // any tombstone), and the two cases become the same test run twice.
            if (moveReachesTheRelayFirst) {
                moverPushes.set(true)
                // The deleter applies the move: the row appears at the new path.
                system.syncUntil("the move's tombstone for $OLD_PATH reached the relay") {
                    tombstoneCount(system, ownerId) > tombstonesBefore
                }
                system.syncUntil("the deleter applied the move: $NEW_PATH is on it") { rowAt(deleter, NEW_PATH) != null }
            } else {
                deleterPushes.set(true)
                // The mover applies the delete. Its own `/src/x.bin` row is already gone (it moved
                // it), so the arrival is observed through the synced `FileDeletedNotification` the
                // delete writes — the only side effect of the deleter's delete that is visible on a
                // device which has already retired that path itself.
                system.syncUntil("the delete and its tombstone reached the relay, and the mover pulled them") {
                    OLD_PATH in deletedNotificationPaths(mover.executionContext) &&
                        tombstoneCount(system, ownerId) > tombstonesBefore
                }
            }
            val afterFirst = tombstoneCount(system, ownerId)
            if (moveReachesTheRelayFirst) deleterPushes.set(true) else moverPushes.set(true)

            // ---- Convergence.
            //
            // The predicate is deliberately the WEAKEST of the claims — the content id and the
            // folder membership are asserted below instead, where they fail with a message rather
            // than as an opaque timeout. What rides along here is the second half of the ordering:
            // the second device's tombstone for the SAME path arrives in its own phase, after the
            // first's. Two tombstones for one path is the idempotence the whole claim rests on.
            system.syncUntil("the second device's edit arrived and both converged on $NEW_PATH") {
                tombstoneCount(system, ownerId) > afterFirst &&
                    listOf(mover, deleter).all { rowAt(it, NEW_PATH) != null && rowAt(it, OLD_PATH) == null }
            }
            // ⚠ And it has to STAY converged. A reap or a late tombstone is a later tick's event, so
            // tick both until two rounds in a row push nothing.
            system.syncUntilQuiet()

            for (device in listOf(mover, deleter)) {
                val who = device.deviceId
                // 1. The documented outcome.
                assertNotNull(
                    rowAt(device, NEW_PATH),
                    "[$who] the move wins: $NEW_PATH survives the concurrent delete of $OLD_PATH",
                )
                assertNull(
                    rowAt(device, OLD_PATH),
                    "[$who] $OLD_PATH is gone — both sides tombstoned it, idempotently",
                )

                // 2. The reference is live: the survivor still names the original content.
                assertEquals(
                    contentId,
                    contentIdOf(device, NEW_PATH),
                    "[$who] $NEW_PATH still carries the original contentId — this is what the " +
                        "reclamation walk has to see, and what makes the surviving row readable",
                )

                // 3. Folder membership, both ends of the move.
                assertTrue(
                    NEW_PATH in childPathsOf(device, TARGET_FOLDER),
                    "[$who] $NEW_PATH is a member of $TARGET_FOLDER's FILES and was not reaped as " +
                        "a dead member; got ${childPathsOf(device, TARGET_FOLDER)}",
                )
                assertTrue(
                    OLD_PATH !in childPathsOf(device, SOURCE_FOLDER),
                    "[$who] $SOURCE_FOLDER no longer lists $OLD_PATH; " +
                        "got ${childPathsOf(device, SOURCE_FOLDER)}",
                )
            }
        } finally {
            system.shutdown()
        }
    }

    /**
     * The window held: the relay saw neither edit, and neither device saw the other's.
     *
     * ⚠ All four checks, not just the relay's. A test that only asked the relay would still pass if
     * the two devices had somehow exchanged blocks directly, and one that only asked the devices
     * would pass if a push had landed but not yet been pulled — in which case the *next* pull makes
     * the second edit causally after the first, and the merge under test never happens.
     */
    private fun assertNothingCrossed(
        system: TestSystem,
        ownerId: String,
        tombstonesBefore: Int,
        mover: TestClient,
        deleter: TestClient,
    ) {
        assertEquals(
            tombstonesBefore,
            tombstoneCount(system, ownerId),
            "the push gates are shut, so neither the move's nor the delete's tombstone for " +
                "$OLD_PATH reached the relay — without this the two edits are sequential and " +
                "nothing merges",
        )
        assertNull(
            rowAt(deleter, NEW_PATH),
            "the deleter has not learned of the move; it deleted $OLD_PATH believing it was still there",
        )
        assertNotNull(
            rowAt(mover, NEW_PATH),
            "precondition: the mover really did move the file locally",
        )
        // ⚠ The deleter's own copy first: without it, "the mover has not learned of the delete"
        // passes just as well when the notification is never written or never readable, and the
        // delete-first case's wait on that same signal would then be waiting on nothing.
        assertTrue(
            OLD_PATH in deletedNotificationPaths(deleter.executionContext),
            "the deleter wrote a FileDeletedNotification for $OLD_PATH; " +
                "got ${deletedNotificationPaths(deleter.executionContext)}",
        )
        assertTrue(
            OLD_PATH !in deletedNotificationPaths(mover.executionContext),
            "the mover has not learned of the delete",
        )
    }

    private fun tombstoneCount(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getTombstones(ownerId, sinceTimestamp = 0L, limit = 10_000).tombstones.size

    private fun idOf(client: TestClient, path: String): DataId =
        DataId.StringDataId.create(
            context = client.executionContext.context,
            type = File.ID,
            keyValue = path,
        )

    private fun rowAt(client: TestClient, path: String): Data? =
        client.executionContext.database.getData(idOf(client, path))

    private fun contentIdOf(client: TestClient, path: String): String? =
        rowAt(client, path)?.let { row ->
            (client.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        }

    /** The paths of a folder's direct children, as its stored `FILES` array names them. */
    private fun childPathsOf(client: TestClient, folderPath: String): Set<String> {
        val folder = rowAt(client, folderPath) ?: return emptySet()
        return client.executionContext.resolveListField(folder, Folder.Fields.FILES)
            .mapNotNull { pathOf(it) }
            .toSet()
    }

    private fun pathOf(data: Data): String? = when (data) {
        is Entity<*> -> runCatching { data.getFieldValue<TString>(File.Field.PATH).get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(File.Field.PATH).get() }.getOrNull()
        else -> null
    }

    /**
     * The paths named by every `FileDeletedNotification` this device holds. Read straight off the
     * notification relation, the way [NotificationSyncSystemTest] reads its own.
     */
    private fun deletedNotificationPaths(executionContext: ExecutionContext): Set<String> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = NotificationService.Relations.NOTIFICATIONS,
            ),
            isEntity = false,
            elementSchemaId = Notification.ID,
        )
        val entries = executionContext.getArrayData(ref)?.toList() ?: emptyList()
        return entries.mapNotNull { entry ->
            when (entry) {
                is Entity<*> -> runCatching {
                    entry.getFieldValue<TString>(FileDeletedNotification.Fields.PATH).get()
                }.getOrNull()

                is Composite -> runCatching {
                    entry.getField<TString>(FileDeletedNotification.Fields.PATH).get()
                }.getOrNull()

                else -> null
            }
        }.toSet()
    }
}
