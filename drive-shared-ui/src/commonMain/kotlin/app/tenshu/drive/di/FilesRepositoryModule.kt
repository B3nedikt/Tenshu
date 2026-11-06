package app.tenshu.drive.di

import app.tenshu.drive.data.driveControlReachability
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.TenshuClient
import app.tenshu.ingest.backup.TenshuIngestRegistry
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FileMapper
import app.tenshu.drive.data.FilesRepository
import app.tenshu.ingest.backup.FullMediaAccessProvider
import app.tenshu.ingest.backup.MediaAccessProvider
import app.tenshu.drive.ui.common.preview.ImageRepository
import app.tenshu.ingest.backup.BackupDataPlane
import app.tenshu.ingest.backup.IngestRegistry
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.TenshuContactsRepository
import app.tenshu.drive.data.TenshuMembershipRepository
import app.tenshu.drive.data.TenshuFilesRepository
import app.tenshu.ingest.backup.BackupSource
import app.tenshu.ingest.backup.NewFolderAnnouncer
import app.tenshu.ingest.backup.NewFolderDecisions
import app.tenshu.ingest.backup.NoopNewFolderAnnouncer
import app.tenshu.ingest.backup.TenshuDuplicateContentGate
import app.tenshu.ingest.IngestSettingsStore
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.galleryBackupSource
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import org.koin.core.qualifier.named
import org.koin.dsl.module
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus

fun filesRepositoryModule() = module {
    includes(domainModule)

    // The scope the reachability probe and the SK-1 mirror live on. ⚠ App-lifetime, not
    // screen-lifetime: the surface must keep telling the truth on a screen that reads nothing.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // SK-1b: the seam between the schema announce (written in `tenshuClientModule`) and the
    // banner's words. A single, because the writer and the reader resolve in either order.
    single { MutableSchemaSkewSource() }

    /**
     * Drive's one Control-reachability engine (`ADR 0025`), and the app's last adoption.
     *
     * ⚠⚠ **Here rather than in `tenshuClientModule`, which is where the announce lives.** Every
     * other app registers its engine in its repository module, and Drive's integration tests
     * compose exactly these modules without the client one — so putting it beside the announcer
     * built a graph that production had and `FilesScreenIntegrationTest` did not, which is a
     * `NoDefinitionFoundException` at the first composition rather than a compile error.
     *
     * ⚠ Only `schemaSkew` is wired: Drive derives `Transport` from `proxyConnected` and the fence
     * from its own stream, and two paths to one banner state would be two sources of truth.
     */
    single {
        driveControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            // ADR 0025 §Decision 8: Control installed and answering, no realm on this device → the
            // wall. The same two reads as Control's landing router; `get()` throws on a failed read,
            // which the engine reads as "cannot tell".
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(get<TenshuClient>().let { client ->
                {
                    realmPresence(
                        isLocked = client.syncService.getUnlockStatus().get().isLocked.get(),
                        ownerId = client.syncService.getSyncStatus().get().ownerId?.get(),
                    )
                }
            }),
        )
    }
    single<FilesRepository> { TenshuFilesRepository(get(), get(), get(), get()) }
    // PH-4b: the drain takes the narrow `BackupDataPlane`, not Drive's whole repository. Bound to
    // the same instance — Drive's `FilesRepository` *is* the data plane; the seam only limits what
    // the drain can see of it.
    single<BackupDataPlane> { get<FilesRepository>() }
    // Manage access / contact handshake surface (drive-manage-access-ux.md §4.1).
    single<ContactsRepository> { TenshuContactsRepository(get()) }
    // Cross-user shared-realm grant / accept / roster (shared-realm-membership-grant.md GR-4).
    single<MembershipRepository> { TenshuMembershipRepository(get()) }
    single { ImageRepository() }
    single { FileMapper() }
    // App-scoped: the folder-upload loop must outlive any single ViewModel /
    // Activity (Phase 3a — docs/proposals/drive-folder-upload.md §3a). Its
    // default CoroutineScope is process-lifetime; survives ViewModel teardown.
    single { FolderUploadController(get()) }
    // Drive's view of Control's ingest registry — registers the gallery source +
    // reports backup status over IPC (Phase 2a-ii, drive-synced-folders-phase2.md §3).
    // ⚠⚠ ADR 0025 §8.6: `SyncedFolderController.start()` registers every backup source and mirrors
    // its status into synced `IngestSource` rows at EVERY launch, whether or not backup is enabled
    // and independent of the storage gate that holds the uploads themselves. Through the realm gate:
    // each write waits for it and retries after a refusal, so the controller's collectors simply
    // write their latest value once a realm appears.
    single<IngestRegistry> { RealmGatedIngestRegistry(TenshuIngestRegistry(get()), get<ControlReachability>()) }
    // App-scoped, process-lifetime: the auto-backup loop runs while the app is
    // alive even when the Synced Folders screen is never opened (§3.4 Layer 2).
    // The engine drives a List<BackupSource> (Phase 3a). The gallery source is
    // always present (built from the gallery MediaSourceProvider + BackupPreferences
    // flows); a platform may contribute extra sources under the "extraBackupSources"
    // qualifier — Android adds the SAF document-tree source there (Phase 3b), desktop
    // binds nothing (folder backup is Control-hosted). start() is called at app
    // startup (DriveApplication / desktop Main).
    // Applies a new-folder prompt's answer (Android's notification-action receiver
    // resolves this); pure preference read-modify-write, no platform dependency.
    single { NewFolderDecisions(get<BackupPreferences>()) }
    single {
        val preferences = get<BackupPreferences>()
        val sources = listOf(galleryBackupSource(get<MediaSourceProvider>(), preferences)) +
            getOrNull<List<BackupSource>>(named("extraBackupSources")).orEmpty()
        // The new-folder prompt surface is Android-only; desktop binds nothing and
        // falls back to the no-op, which makes the scan itself inert there.
        val announcer = getOrNull<NewFolderAnnouncer>() ?: NoopNewFolderAnnouncer
        // Android 14 partial media access gates the prompt; platforms without the
        // concept report FULL (see MediaAccessProvider).
        val mediaAccess = getOrNull<MediaAccessProvider>() ?: FullMediaAccessProvider
        SyncedFolderController(
            preferences, sources, get(), get(), get(), announcer, mediaAccess,
            // PH-6: the ingest-time content skip. ⚠ Inert on desktop even though it is wired
            // there — Control's `FolderWatcher` owns ingest and this process only registers.
            duplicates = TenshuDuplicateContentGate(get(), IngestSettingsStore(get())),
        )
    }
}

/**
 * [IngestRegistry] behind the realm write gate (ADR 0025 §8.6): every write waits until writes may
 * land, refuses while they may not, and after a refusal waits for the next release and writes again.
 * Reads pass straight through.
 *
 * ⚠⚠ A status can never be lost to the order the held writes resume in (Codex P2 on bdbf30de0).
 * `SyncedFolderController`'s registration and status collectors both suspend here while the gate
 * holds and resume independently on release; `TenshuIngestRegistry.updateStatus` is a no-op until the
 * source row exists, and the controller's `distinctUntilChangedBy` never re-emits an unchanged
 * status — so a status that resumed first was dropped and Control's inventory stayed stale. So the
 * latest status per source is remembered, a status write always sends that latest value, and a
 * successful registration re-applies it.
 *
 * ⚠⚠ One lock, held across each whole write sequence (Codex P1 + P2 on 0686fa2cf). The replay is a
 * second synced write, so it re-checks the gate itself — a verdict that moved to RECHECKING or
 * ABSENT while `registerSource` was in flight refuses it, and the whole registration retries on
 * the next release (the registration is an idempotent upsert). And because status writes take the
 * same lock and always read the latest value under it, a newer status can never be overtaken by a
 * replay of an older one. ⚠ The lock is never held across the gate's suspend-until-writable wait:
 * the wait comes first, then the lock, then a non-suspending re-check before every delegate write.
 */
internal class RealmGatedIngestRegistry(
    private val delegate: IngestRegistry,
    private val gate: ControlReachability,
) : IngestRegistry by delegate {
    /** Guards [latestStatus] and serialises every delegate write. The volume is a few rows per launch. */
    private val writeLock = kotlinx.coroutines.sync.Mutex()
    private val latestStatus = mutableMapOf<String, Pair<String, Long>>()

    override suspend fun registerSource(
        sourceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String,
    ) = gatedUnderLock {
        delegate.registerSource(sourceId, displayName, sourceKind, watchedPaths)
        latestStatus[sourceId]?.let { (status, pending) ->
            gate.refuseIfRealmWritesHeld()
            delegate.updateStatus(sourceId, status, pending)
        }
    }

    override suspend fun updateStatus(sourceId: String, status: String, pendingCount: Long) {
        writeLock.withLock { latestStatus[sourceId] = status to pendingCount }
        gatedUnderLock {
            val (latest, pending) = latestStatus.getValue(sourceId)
            delegate.updateStatus(sourceId, latest, pending)
        }
    }

    /**
     * Waits for the gate WITHOUT the lock, then runs [writes] under it after a non-suspending re-check.
     * [writes] re-checks before any further delegate write it makes; a refusal anywhere waits for the
     * next release and runs the whole sequence again.
     */
    private suspend fun gatedUnderLock(writes: suspend () -> Unit) {
        while (true) {
            gate.awaitRealmWritable()
            try {
                writeLock.withLock {
                    gate.refuseIfRealmWritesHeld()
                    writes()
                }
                return
            } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
                // Held between the wait and a write; wait for the next release.
            }
        }
    }
}
