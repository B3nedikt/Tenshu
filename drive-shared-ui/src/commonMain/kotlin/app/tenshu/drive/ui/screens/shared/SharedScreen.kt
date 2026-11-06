package app.tenshu.drive.ui.screens.shared

import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.drive.shared.resources.my_shares_error_title
import app.tenshu.drive.shared.resources.my_shares_error_body
import app.tenshu.liststate.ListLoadPhase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.contact_prompt_banner
import app.tenshu.drive.shared.resources.contact_prompt_grant_body
import app.tenshu.drive.shared.resources.contact_prompt_grant_confirm
import app.tenshu.drive.shared.resources.contact_prompt_grant_skip
import app.tenshu.drive.shared.resources.contact_prompt_grant_title
import app.tenshu.drive.shared.resources.contact_prompt_unnamed
import app.tenshu.drive.shared.resources.shared_with_me_accept_action
import app.tenshu.drive.shared.resources.shared_with_me_accept_none
import app.tenshu.drive.shared.resources.shared_with_me_accept_picker_empty
import app.tenshu.drive.shared.resources.shared_with_me_accept_picker_title
import app.tenshu.drive.shared.resources.shared_with_me_pending_accept
import app.tenshu.drive.shared.resources.shared_with_me_pending_decline
import app.tenshu.drive.shared.resources.shared_with_me_pending_grant_body
import app.tenshu.drive.shared.resources.shared_with_me_pending_grant_title
import app.tenshu.drive.shared.resources.redeem_invite_accepted_title
import app.tenshu.drive.shared.resources.redeem_invite_gone
import app.tenshu.drive.shared.resources.redeem_invite_rejected
import app.tenshu.drive.shared.resources.redeem_invite_waiting_row
import app.tenshu.drive.shared.resources.shared_redeem_invite_action
import app.tenshu.drive.shared.resources.shared_title
import app.tenshu.drive.shared.resources.my_shares_copy_id
import app.tenshu.drive.shared.resources.my_shares_empty_body
import app.tenshu.drive.shared.resources.my_shares_empty_title
import app.tenshu.drive.shared.resources.my_shares_revoke
import app.tenshu.drive.shared.resources.shared_folders_links_section
import app.tenshu.drive.shared.resources.shared_folders_section
import app.tenshu.drive.shared.resources.shared_tab_my_shares
import app.tenshu.drive.shared.resources.shared_tab_shared_with_me
import app.tenshu.drive.shared.resources.shared_with_me_empty_body
import app.tenshu.drive.shared.resources.shared_with_me_empty_title
import app.tenshu.drive.shared.resources.shared_with_me_unnamed
import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.ui.contacts.ContactPromptDialog
import app.tenshu.drive.ui.contacts.ContactPromptViewModel
import app.tenshu.drive.ui.contacts.displayFingerprint
import app.tenshu.drive.ui.share.ShareLinkUrl
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.tenshu.contact.AwaitInviteAck

/**
 * Two-tab Shared destination (`docs/file-sharing.md`).
 *
 * *Shared with me* is empty-state-only — Mode B (named-recipient sharing) lands
 * with `drive-shared-realms.md`. *My shares* projects the realm-wide
 * `FileService.shares` collection with per-row *Copy link* (regenerates the
 * URL fragment from the live VM state — see caveat below) and *Revoke*.
 *
 * **K_share caveat.** A row's *Copy link* button only works for shares minted
 * in the **current session**: the URL fragment (`K_share`) is intentionally
 * never persisted — it's the read capability (A11). Shares minted on another
 * device, or in a prior session, render without a copy affordance for now (the
 * design intent is that the mint sheet itself is where you copy; the *My
 * shares* list is for inspection + revocation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(
    modifier: Modifier = Modifier,
    onOpenSharedFolder: (realmId: String, name: String) -> Unit = { _, _ -> },
    onRedeemInvite: () -> Unit = {},
    viewModel: SharedScreenViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var overflowOpen by remember { mutableStateOf(false) }

    // Use the same chrome pattern as Control's screens (and Drive's own
    // SharedBrowseScreen): a Scaffold + AppTopAppBar. The top app bar consumes the
    // status-bar / display-cutout inset, so the SecondaryTabRow + content clear the system
    // bars uniformly. (Home/Files get this implicitly from their Material 3
    // SearchBar; this tab has no SearchBar, so a bare SecondaryTabRow would otherwise render
    // under the status bar.)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(Res.string.shared_title),
                actions = {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(Res.string.shared_redeem_invite_action))
                            },
                            onClick = {
                                overflowOpen = false
                                onRedeemInvite()
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // §2.3 completion-prompt banner: any invite at REDEEMED, on any
            // device (the flag is synced state). Tap → the prompt dialog.
            if (uiState.hasRedeemedInvites) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::onContactPromptBannerClicked),
                ) {
                    Text(
                        text = stringResource(Res.string.contact_prompt_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // Deferred grant offers (`contacts-app.md` §9.1 alternative): a contact who
            // finished a folder handshake but was never actually granted the realm. This is
            // the decoupled replacement for the offer that used to be chained onto the
            // completion prompt — derived from synced rows, so it survives dismissing the
            // prompt, closing the app, or completing the handshake on another device.
            uiState.grantOffers.forEach { offer ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(
                                Res.string.contact_prompt_grant_title,
                                offer.name.ifEmpty {
                                    stringResource(Res.string.contact_prompt_unnamed)
                                },
                                offer.label,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.contact_prompt_grant_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(
                                onClick = { viewModel.onGrantOfferDismissed(offer.inviteId) },
                            ) {
                                Text(stringResource(Res.string.contact_prompt_grant_skip))
                            }
                            TextButton(
                                onClick = { viewModel.onGrantOfferAccepted(offer.inviteId) },
                            ) {
                                Text(stringResource(Res.string.contact_prompt_grant_confirm))
                            }
                        }
                    }
                }
            }
            SecondaryTabRow(selectedTabIndex = uiState.activeTab.ordinal) {
                Tab.entries.forEach { tab ->
                    Tab(
                        selected = tab == uiState.activeTab,
                        onClick = { viewModel.onTabSelected(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (uiState.activeTab) {
                Tab.SHARED_WITH_ME -> SharedWithMeContent(
                    realms = uiState.sharedWithMe,
                    resumedRedemptions = uiState.resumedRedemptions,
                    pendingGrants = uiState.pendingGrants,
                    acceptInProgress = uiState.acceptInProgress,
                    onOpenSharedFolder = onOpenSharedFolder,
                    onAcceptClicked = viewModel::onAcceptFromContactClicked,
                    onAcceptPendingGrant = viewModel::onAcceptPendingGrant,
                    onDeclinePendingGrant = viewModel::onDeclinePendingGrant,
                )
                Tab.MY_SHARES -> MySharesContent(
                    uiState = uiState,
                    onRevokeClicked = viewModel::onRevokeShareClicked,
                    onShareRowClicked = viewModel::onShareRowClicked,
                    onOpenSharedFolder = onOpenSharedFolder,
                )
            }
        }
    }

    uiState.promptInviteId?.let { inviteId ->
        val promptViewModel = koinViewModel<ContactPromptViewModel>(
            key = "contact_prompt_$inviteId",
            parameters = { parametersOf(inviteId) }
        )
        ContactPromptDialog(
            viewModel = promptViewModel,
            onDismiss = viewModel::onContactPromptDismissed,
        )
    }

    if (uiState.showAcceptPicker) {
        AppBottomSheet(
            visible = true,
            onDismissRequest = viewModel::onAcceptPickerDismissed,
        ) {
            AcceptGrantPickerSheet(
                contacts = uiState.grantableContacts,
                inProgress = uiState.acceptInProgress,
                nothingFromName = uiState.acceptMessage,
                onAccept = viewModel::onAcceptFromContact,
            )
        }
    }
}

/** GR-4 (Option A): pick a connected contact to accept a shared folder from. */
@Composable
private fun AcceptGrantPickerSheet(
    contacts: List<app.tenshu.drive.data.ContactModel>,
    inProgress: Boolean,
    nothingFromName: String?,
    onAccept: (grantorFingerprint: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.shared_with_me_accept_picker_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (contacts.isEmpty()) {
            Text(
                text = stringResource(Res.string.shared_with_me_accept_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        contacts.forEach { contact ->
            Text(
                text = contact.displayName.ifEmpty {
                    stringResource(Res.string.contact_prompt_unnamed)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !inProgress) { onAccept(contact.fingerprint) }
                    .padding(vertical = 12.dp),
            )
        }
        nothingFromName?.let { name ->
            Text(
                text = stringResource(
                    Res.string.shared_with_me_accept_none,
                    name.ifEmpty { stringResource(Res.string.contact_prompt_unnamed) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Tab.label: String
    @Composable
    get() = when (this) {
        Tab.SHARED_WITH_ME -> stringResource(Res.string.shared_tab_shared_with_me)
        Tab.MY_SHARES -> stringResource(Res.string.shared_tab_my_shares)
    }

@Composable
private fun SharedWithMeContent(
    realms: List<InboundSharedRealm>,
    resumedRedemptions: List<ResumedRedemption>,
    pendingGrants: List<PendingGrantCard>,
    acceptInProgress: Boolean,
    onOpenSharedFolder: (realmId: String, name: String) -> Unit,
    onAcceptClicked: () -> Unit,
    onAcceptPendingGrant: (fingerprint: String) -> Unit,
    onDeclinePendingGrant: (fingerprint: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // GR-6: discovered pending grants (the inbound poll) — a card per quarantined
        // grant with explicit Accept / Decline. Rendered above the inbound list.
        pendingGrants.forEach { card ->
            PendingGrantCardItem(
                card = card,
                acceptInProgress = acceptInProgress,
                onAccept = { onAcceptPendingGrant(card.fingerprint) },
                onDecline = { onDeclinePendingGrant(card.fingerprint) },
            )
        }
        // GR-4: a contact can grant me a folder out-of-band; this is how I pick
        // it up (Option A — pickup+accept by contact, no in-app discovery).
        TextButton(
            onClick = onAcceptClicked,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(Res.string.shared_with_me_accept_action))
        }
        if (realms.isEmpty() && resumedRedemptions.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.shared_with_me_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.shared_with_me_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // §3.2.4 resumed redemptions: a waiting row per live invite-ack wait,
            // flipping to its terminal copy when the ack lands.
            items(resumedRedemptions, key = { "redeem:${it.inviteId}" }) { row ->
                ResumedRedemptionRowItem(row)
            }
            items(realms, key = { "inbound:${it.sharedRealmId}" }) { realm ->
                InboundRealmRowItem(realm = realm, onOpenSharedFolder = onOpenSharedFolder)
            }
        }
    }
}

/**
 * GR-6: a discovered pending grant — the grantor's name (or a generic label until
 * contacts load) with explicit *Accept* / *Decline*. The whole inbound poll exists to
 * give the user a deliberate choice (D4: contact ≠ consent to receive bytes).
 */
@Composable
private fun PendingGrantCardItem(
    card: PendingGrantCard,
    acceptInProgress: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(
                    Res.string.shared_with_me_pending_grant_title,
                    card.grantorName.ifEmpty { stringResource(Res.string.contact_prompt_unnamed) },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(Res.string.shared_with_me_pending_grant_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDecline, enabled = !acceptInProgress) {
                    Text(stringResource(Res.string.shared_with_me_pending_decline))
                }
                TextButton(onClick = onAccept, enabled = !acceptInProgress) {
                    Text(stringResource(Res.string.shared_with_me_pending_accept))
                }
            }
        }
    }
}

@Composable
private fun ResumedRedemptionRowItem(row: ResumedRedemption) {
    val ack = row.ack
    val text = when (ack?.status) {
        null, AwaitInviteAck.Result.PENDING ->
            stringResource(Res.string.redeem_invite_waiting_row)
        AwaitInviteAck.Result.ACCEPTED -> stringResource(
            Res.string.redeem_invite_accepted_title,
        ) + " — " + ack.peerName.ifEmpty { stringResource(Res.string.contact_prompt_unnamed) }
        AwaitInviteAck.Result.REJECTED -> stringResource(Res.string.redeem_invite_rejected)
        else -> stringResource(Res.string.redeem_invite_gone)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = displayFingerprint(row.peerFingerprint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InboundRealmRowItem(
    realm: InboundSharedRealm,
    onOpenSharedFolder: (realmId: String, name: String) -> Unit,
) {
    // The name is read from the realm's wrapper-root SharedFile (3d.2b-i); until
    // that node has synced in it's null, so show a neutral fallback label.
    val displayName = realm.name ?: stringResource(Res.string.shared_with_me_unnamed)
    Text(
        text = displayName,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSharedFolder(realm.sharedRealmId, displayName) },
    )
}

@Composable
private fun MySharesContent(
    uiState: SharedScreenUiState,
    onRevokeClicked: (String) -> Unit,
    onShareRowClicked: (String) -> Unit,
    onOpenSharedFolder: (realmId: String, name: String) -> Unit,
) {
    // *My shares* (outbound) groups the two share modes: Mode-B shared folders
    // (drive-shared-realm-file-model.md §6 Phase 3d) + Mode-A link shares. Empty
    // only when neither exists.
    if (uiState.myShares.isEmpty() && uiState.sharedFolders.isEmpty()) {
        // ⚠ Three states, not two. `myShares` comes from a reactive read that used to fold its
        // failure to an empty list, so a broken read rendered as "You haven't shared anything
        // yet" — a claim about what the user has done, made when the read that would know had
        // failed. `sharedFolders` is derived locally, so only the shares half needs the phase.
        when (uiState.mySharesLoad) {
            ListLoadPhase.LOADING -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppLoadingIndicator()
            }

            // No retry button — the read retries itself (see [FilesList]).
            ListLoadPhase.FAILED -> Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.my_shares_error_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.my_shares_error_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ListLoadPhase.LOADED -> Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.my_shares_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.my_shares_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.sharedFolders.isNotEmpty()) {
            item(key = "folders-header") {
                SectionHeader(stringResource(Res.string.shared_folders_section))
            }
            items(uiState.sharedFolders, key = { "realm:${it.sharedRealmId}" }) { folder ->
                SharedFolderRowItem(
                    folder = folder,
                    onClick = { onOpenSharedFolder(folder.sharedRealmId, folder.name) },
                )
            }
        }
        if (uiState.myShares.isNotEmpty()) {
            item(key = "links-header") {
                SectionHeader(stringResource(Res.string.shared_folders_links_section))
            }
            items(uiState.myShares, key = { it.shareId }) { row ->
                MyShareRowItem(
                    row = row,
                    onRevokeClicked = onRevokeClicked,
                    onShareRowClicked = onShareRowClicked,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SharedFolderRowItem(folder: SharedFolderModel, onClick: () -> Unit) {
    // Tapping browses the realm via the shared-realm read ops (3d.2b): the
    // SharedBrowse destination lists `listSharedFolder` and opens `readSharedFile`.
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = folder.parentPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MyShareRowItem(
    row: MyShareRow,
    onRevokeClicked: (String) -> Unit,
    onShareRowClicked: (String) -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.fileName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = row.filePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                // Copy the relay-side path-shaped URL only (no fragment) —
                // the URL fragment is the secret key and is not persisted
                // anywhere by design (A11). The mint sheet is where the
                // user copies the full capability URL. Shared with iOS'
                // My-shares row menu via `ShareLinkUrl.pathOnly`.
                clipboardManager.setText(AnnotatedString(ShareLinkUrl.pathOnly(row.shareId)))
            }
        ) {
            Text(stringResource(Res.string.my_shares_copy_id))
        }
        TextButton(onClick = { onRevokeClicked(row.shareId) }) {
            Text(stringResource(Res.string.my_shares_revoke))
        }
    }
}
