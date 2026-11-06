package app.tenshu.control.ui.screens.shares

import app.tenshu.control.data.ShareSummary
import app.tenshu.control.data.SharedFolderSummary

/**
 * State for the Data → Shares inventory (`control-ia.md` §5.3).
 *
 * [shares] is already newest-first (the `ShareManager` sorts it) and keeps
 * revoked rows so the list can render a Revoked badge + history. [sharedFolders]
 * is owned-first then joined (`orderSharedFolders`) and empty when the realm is in
 * none — the group only renders when non-empty. [pendingRevokeShareId] drives the
 * revoke-confirm dialog: non-null while the user is confirming a revoke of that share.
 */
data class SharesUiState(
    val shares: List<ShareSummary> = emptyList(),
    val sharedFolders: List<SharedFolderSummary> = emptyList(),
    val pendingRevokeShareId: String? = null,
)
