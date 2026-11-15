package app.tenshu.drive.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.tenshu.drive.data.BackupFolderPickResult
import app.tenshu.ingest.backup.AndroidMediaAccessProvider
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.ui.App
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject
import org.koin.compose.viewmodel.koinViewModel
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.OpenTargetIntents

class MainActivity : ComponentActivity() {

    private val client: TenshuClient by inject()
    private val backupPreferences: BackupPreferences by inject()

    /**
     * Where a cross-app open goes (`control-cross-app-open.md` TT-2a).
     *
     * ⚠⚠ Injected rather than constructed here: the **same** inbox is what the transport pushes a
     * warm request into, so a second instance would make that path deliver to something nobody
     * collects — and it is the silent one, since a cold start visibly launches the app either way.
     */
    private val openTargets: OpenTargetInbox by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Contact-invite deep link (drive-manage-access-ux.md §3.1): a VIEW
        // intent for tenshu-invite: or https://tenshu.app/i lands on the
        // redeem screen. `dataString` keeps the URI fragment (the link's
        // payload half) intact; parse errors surface inside the screen.
        val inviteLink = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.dataString
            ?.takeIf { it.startsWith("tenshu-invite:") || it.startsWith("https://tenshu.app/i") }

        // New-folder prompt (drive-synced-folders-new-folder-prompt.md): tapping the
        // notification body — rather than one of its inline actions — lands on
        // Synced Folders, where the folder can be reviewed alongside every other.
        val openSyncedFolders = intent?.getBooleanExtra(EXTRA_OPEN_SYNCED_FOLDERS, false) == true

        // ⚠ Guarded on a genuine launch: a rotation re-delivers the same intent, and re-opening the
        // viewer over whatever the user had moved on to is the same class of bug as re-redeeming an
        // invite.
        if (savedInstanceState == null) intent?.let(::readOpenTarget)

        setContent {
            val addFileActionsViewModel: AddFileActionsViewModel = koinViewModel()
            val coroutineScope = rememberCoroutineScope()
            var pendingParentPath by remember { mutableStateOf<String?>(null) }
            var pendingBackupFolderResult by remember {
                mutableStateOf<((BackupFolderPickResult) -> Unit)?>(null)
            }

            // Phase 3b — add a SAF folder to auto-backup. Persist the tree grant so
            // the headless drain keeps read access across process restarts, then
            // record the URI (the doc-tree source's selected "bucket"). Removal +
            // grant release happen in SyncedFoldersViewModel. `runCatching`: not
            // every picked tree offers a persistable grant (SecurityException).
            val addBackupFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
                onResult = { uri ->
                    val report = pendingBackupFolderResult
                    pendingBackupFolderResult = null
                    if (uri == null) {
                        report?.invoke(BackupFolderPickResult.Cancelled)
                        return@rememberLauncherForActivityResult
                    }
                    // Persistence must not depend on the reporting callback: Activity recreation
                    // can clear that in-memory lambda while the system picker still returns a URI.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    coroutineScope.launch { backupPreferences.addDocumentTreeUri(uri.toString()) }
                    report?.invoke(BackupFolderPickResult.Added(uri.toString()))
                }
            )

            // Gallery backup's media grant (drive-synced-folders-new-folder-prompt.md
            // §5). Requested as ONE multi-permission call — on API 34+ that array
            // includes READ_MEDIA_VISUAL_USER_SELECTED, and splitting it would stack
            // two system dialogs. The result is deliberately ignored: the Synced
            // Folders screen re-reads the real grant on resume, which is also the
            // path that covers a change made from system settings.
            val requestMediaAccessLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
                onResult = { }
            )

            // Multi-select pickers, as Google Drive's own Upload does: each picked item is its
            // own upload, so N picks are N `uploadFile` calls. An empty list is a cancel.
            val openDocumentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments(),
                onResult = { uris ->
                    pendingParentPath?.let { path ->
                        uris.forEach { addFileActionsViewModel.uploadFile(it.toString(), path) }
                    }
                }
            )

            val openDocumentTreeLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
                onResult = { uri ->
                    pendingParentPath?.let { path ->
                        if (uri == null) return@let
                        // Persist the tree grant so an in-flight folder upload
                        // (Phase 3a) survives a transient Activity teardown /
                        // process restart — without it the grant is
                        // process-volatile and a backgrounded walk loses read
                        // access mid-enumeration. `runCatching`: the system
                        // does not offer a persistable grant for every picked
                        // tree (SecurityException); the foreground upload can
                        // still proceed. See docs/proposals/drive-folder-upload.md §3a.
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        addFileActionsViewModel.uploadFolder(uri.toString(), path)
                    }
                }
            )

            val pickVisualMediaLauncher = rememberLauncherForActivityResult(
                // No explicit `maxItems`: the default is the system photo picker's own limit
                // (`MediaStore.getPickImagesMaxLimit()`), and a larger value throws on launch.
                contract = ActivityResultContracts.PickMultipleVisualMedia(),
                onResult = { uris ->
                    pendingParentPath?.let { path ->
                        uris.forEach { addFileActionsViewModel.uploadFile(it.toString(), path) }
                    }
                }
            )

            LaunchedEffect(client) {
                client.init()
            }

            TenshuTheme {
                App(
                    fabViewModel = addFileActionsViewModel,
                    onUploadFile = { path ->
                        pendingParentPath = path
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onUploadFolder = { path ->
                        pendingParentPath = path
                        openDocumentTreeLauncher.launch(null)
                    },
                    onUploadImages = { path ->
                        pendingParentPath = path
                        pickVisualMediaLauncher.launch(PickVisualMediaRequest())
                    },
                    onAddBackupFolder = { report ->
                        pendingBackupFolderResult = report
                        addBackupFolderLauncher.launch(null)
                    },
                    onRequestMediaAccess = {
                        requestMediaAccessLauncher.launch(
                            AndroidMediaAccessProvider.requiredPermissions()
                        )
                    },
                    inviteLinkToRedeem = inviteLink,
                    openSyncedFolders = openSyncedFolders,
                    openTargets = this@MainActivity.openTargets.targets,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.destroy()
    }

    /**
     * The cold-start half of a cross-app open (CO-D3): Control could not deliver over IPC because
     * Drive was not connected, so the target rode the launch itself.
     *
     * ⚠ No-ops for an ordinary launch — the extra is simply absent.
     */
    private fun readOpenTarget(intent: Intent) {
        OpenTargetIntents.targetFrom(intent) { getKoin().get() }?.let(openTargets::deliver)
    }

    /**
     * ⚠⚠ **Drive had no `onNewIntent` at all, and a cross-app open is the first thing that needs
     * one.** Control mints a launch ticket whenever the handler must be brought forward — including
     * when it is already running (§5e.1) — so the common case is a launch that RESUMES this
     * activity and never reaches `onCreate`. Reading only there would make a warm open a silent
     * no-op, which is the exact failure §5e.1 was written about.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenTarget(intent)
    }

    companion object {
        /** Set by the new-folder prompt's content intent. */
        const val EXTRA_OPEN_SYNCED_FOLDERS = "openSyncedFolders"
    }
}
