package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.DisplayPreferences
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.NoopDisplayPreferences
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.data.TenshuOfflinePreferences
import app.tenshu.drive.data.TenshuSyncRulesPreferences
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.coroutines.CoroutineContext

/**
 * Wires the **real** Drive view-model layer (`:drive-shared-logic`) to iOS so a SwiftUI screen can
 * resolve [FilesScreenViewModel] + [AddFileActionsViewModel] and observe their paged
 * `StateFlow<PagingData<FileUiModel>>` through [TenshuPagingPresenter] (proposal §9.3 "share view
 * models + repos, native UI").
 *
 * The data source is **injected** [FilesRepository] — the seam that makes the UI testable:
 *  - dev / previews / tests install a configurable [FakeFilesRepository] (the default), so every UI
 *    state is drivable without a proxy;
 *  - production installs the real `TenshuFilesRepository` over the EPC channel ([installRealClient]),
 *    once a Control serves EPC (the on-device data path).
 *
 * Drive's Koin DI lives in `:drive-shared-ui` (Compose, not on iOS), so this is the minimal hand-wiring
 * the SwiftUI client needs instead of `viewModelsModule()`.
 */
object DriveReadSurface {

    // The repositories every [create] (without an explicit one) binds. This is the single install seam
    // for the whole app: the read/detail/mutation surfaces bind [installed]; the sharing cluster
    // ([DriveShareSurface]) binds [installedContacts] + [installedMembership]. Defaults = fakes so every
    // surface works with no proxy; production swaps in the real client via [installRealClient].
    private var installed: FilesRepository = FakeFilesRepository()
    private var installedContacts: ContactsRepository = FakeContactsRepository()
    private var installedMembership: MembershipRepository = FakeMembershipRepository()
    private var installedOfflinePreferences: OfflinePreferences = NoopOfflinePreferences
    private var installedSyncRulesPreferences: SyncRulesPreferences = NoopSyncRulesPreferences
    private var installedDisplayPreferences: DisplayPreferences = NoopDisplayPreferences

    /**
     * Install the [FilesRepository] subsequently-[create]d surfaces bind. Production installs a real
     * `TenshuFilesRepository` (see [installRealClient]); a test/preview installs a configured
     * [FakeFilesRepository] before building its surface.
     */
    fun installRepository(repository: FilesRepository) {
        installed = repository
    }

    /**
     * Install the contacts + membership repos the sharing cluster binds. Mirrors [installRepository]
     * for the access-management surfaces; defaults stay the fakes until [installRealClient] swaps in the
     * real client.
     */
    fun installSharingRepositories(
        contactsRepository: ContactsRepository,
        membershipRepository: MembershipRepository,
    ) {
        installedContacts = contactsRepository
        installedMembership = membershipRepository
    }

    /** The currently-installed repository — sibling surfaces (e.g. `DriveDetailSurface`) bind the same one. */
    internal fun installedRepository(): FilesRepository = installed

    /** The currently-installed contacts repo — the sharing cluster binds this. */
    internal fun installedContactsRepository(): ContactsRepository = installedContacts

    /** The currently-installed membership repo — the sharing cluster binds this. */
    internal fun installedMembershipRepository(): MembershipRepository = installedMembership

    /**
     * P5 (ios-drive-parity.md): install the REAL per-device preference seams — offline pinning,
     * sync rules, and the listing display prefs (sort + list/grid view mode) over the
     * `:preference-service` — alongside the repositories. Defaults stay the Noop seams so
     * fakes-era surfaces keep working; the live installs ([installRealClient] /
     * `DriveLocalRuntime.install`) swap them in with the client.
     *
     * [displayPreferences] is trailing + defaulted so existing call sites that only wire the
     * two P5 seams stay source-compatible while the sort/view-mode install lands.
     */
    fun installPreferences(
        offlinePreferences: OfflinePreferences,
        syncRulesPreferences: SyncRulesPreferences,
        displayPreferences: DisplayPreferences = NoopDisplayPreferences,
    ) {
        installedOfflinePreferences = offlinePreferences
        installedSyncRulesPreferences = syncRulesPreferences
        installedDisplayPreferences = displayPreferences
    }

    internal fun installedOfflinePreferences(): OfflinePreferences = installedOfflinePreferences

    internal fun installedSyncRulesPreferences(): SyncRulesPreferences = installedSyncRulesPreferences

    internal fun installedDisplayPreferences(): DisplayPreferences = installedDisplayPreferences

    /**
     * Production path: assemble the real client over the established EPC [channel] and install **all
     * three** repositories (files + contacts + membership) from the one assembly — a single call swaps
     * the entire app (files + sharing) off the fakes. The live data round-trip needs a Control serving
     * EPC over a reachable relay (the on-device gate); this is the one call the app makes once that
     * channel is up.
     */
    fun installRealClient(channel: TaskTransport) {
        val client = EpcDriveClient.assembleClient(channel)
        installRepository(client.filesRepository)
        installSharingRepositories(client.contactsRepository, client.membershipRepository)
        installPreferences(
            TenshuOfflinePreferences(client.tenshuClient),
            TenshuSyncRulesPreferences(client.tenshuClient),
            app.tenshu.drive.data.TenshuDisplayPreferences(client.tenshuClient),
        )
    }

    /**
     * Route-1 production path (ios-drive-parity.md P1a): assemble the **in-process** proxy runtime
     * over the shared App-Group replica ([DriveLocalRuntime]) and install all three repositories
     * from it — same-device Drive↔Control needs no channel and no awake Control (the wake wall).
     * Idempotent.
     */
    fun installLocalRuntime() {
        DriveLocalRuntime.install()
    }

    /**
     * Restore the default fake repositories across all three surfaces — the live-client teardown /
     * fallback path ([LiveClientSession.teardown]). Surfaces built after this go back to canned data, so
     * a dropped channel returns the app to its no-proxy state instead of a dead live client.
     */
    fun installFakes() {
        installed = FakeFilesRepository()
        installedContacts = FakeContactsRepository()
        installedMembership = FakeMembershipRepository()
        installedOfflinePreferences = NoopOfflinePreferences
        installedSyncRulesPreferences = NoopSyncRulesPreferences
        installedDisplayPreferences = NoopDisplayPreferences
    }

    /**
     * The view models for one folder, plus a presenter already collecting the paged listing.
     * [viewModel] backs the list; [addFileActions] backs the **+ Add** toolbar action; both share one
     * [FilesRepository] so an upload invalidates the same listing.
     */
    class Bound internal constructor(
        val viewModel: FilesScreenViewModel,
        val addFileActions: AddFileActionsViewModel,
        val presenter: TenshuPagingPresenter<FileUiModel>,
        /** The folder this surface lists — the parent path single-file uploads land in. */
        val folderPath: String,
    )

    /** App entry point for the realm root: collect on the main dispatcher (live on iOS). */
    fun create(): Bound = create(CoroutineScope(Dispatchers.Main), Dispatchers.Main)

    /** Root-folder surface with explicit dispatchers (the smoke injects `Dispatchers.Default`). */
    fun create(scope: CoroutineScope, presenterContext: CoroutineContext): Bound =
        create(scope, presenterContext, FileModel.Root.path)

    /** A surface for an arbitrary [folderPath] — folder push-nav builds one per child. */
    fun create(folderPath: String): Bound =
        create(CoroutineScope(Dispatchers.Main), Dispatchers.Main, folderPath)

    /** Build the read surface for [folderPath] over the [installed] repository (the SwiftUI path). */
    fun create(
        scope: CoroutineScope,
        presenterContext: CoroutineContext,
        folderPath: String,
    ): Bound = create(installed, scope, presenterContext, folderPath)

    /**
     * Canonical builder — binds [repository] explicitly. Tests/previews call this with a configured
     * [FakeFilesRepository] to drive a specific UI state; the convenience overloads above use the
     * [installed] repository so SwiftUI call sites stay parameterless.
     */
    fun create(
        repository: FilesRepository,
        scope: CoroutineScope,
        presenterContext: CoroutineContext,
        folderPath: String,
    ): Bound {
        val selection = SelectionViewModel(
            repository,
            installedOfflinePreferences,
            installedSyncRulesPreferences,
        )
        val viewModel = FilesScreenViewModel(repository, selection, folderPath, installedDisplayPreferences)
        val addFileActions = AddFileActionsViewModel(repository, FolderUploadController(repository))
        val presenter = TenshuPagingPresenter<FileUiModel>(scope, presenterContext)
        presenter.collect(viewModel.pagedFiles)
        return Bound(viewModel, addFileActions, presenter, folderPath)
    }

    /** Entries in [FakeFilesRepository.DEFAULT_LISTING] — asserted by the smoke. */
    const val DEFAULT_LISTING_COUNT: Int = 4
}

/** Minimal offline-prefs that pins nothing (no proxy to read device prefs from). */
internal object NoopOfflinePreferences : OfflinePreferences {
    override fun observe(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setOffline(paths: List<String>) {}
}
