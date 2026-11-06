package app.tenshu.control.ui.screens.services

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.services_change_identity
import app.tenshu.control.shared.resources.services_delete_account
import app.tenshu.control.shared.resources.services_disconnect
import app.tenshu.control.shared.resources.services_get_more_storage
import app.tenshu.control.shared.resources.services_identity_email
import app.tenshu.control.shared.resources.services_identity_rei
import app.tenshu.control.shared.resources.services_manage_subscription
import app.tenshu.control.shared.resources.services_plan_basic
import app.tenshu.control.shared.resources.services_plan_free
import app.tenshu.control.shared.resources.services_plan_label
import app.tenshu.control.shared.resources.services_plan_premium
import app.tenshu.control.shared.resources.services_relay_empty_body
import app.tenshu.control.shared.resources.services_relay_title
import app.tenshu.control.shared.resources.services_sign_in_to_manage
import app.tenshu.control.shared.resources.services_sign_up
import app.tenshu.control.shared.resources.services_storage_pending
import app.tenshu.control.shared.resources.services_storage_pending_detail
import app.tenshu.control.shared.resources.services_storage_used
import app.tenshu.control.shared.resources.services_synced_body
import app.tenshu.control.shared.resources.services_synced_manage_hint
import app.tenshu.control.shared.resources.services_title
import app.tenshu.control.ui.common.rememberOpenExternalUrlLauncher
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import org.tenshu.storage.common.formatStorageBytes

/**
 * §6.1 Services tab per `docs/proposals/onboarding-low-friction.md`.
 *
 * ⚠⚠ **One card, one source for the numbers** (`commerce-end-to-end-dry-run.md` CE-4 / D5). Plan
 * and usage always come from the synced entitlement; the local commerce session contributes only
 * the identity row and which actions are offered. Until CE-4 there were two rival cards and the
 * synced one was suppressed whenever a local account existed — so signing in on a device *replaced*
 * live figures with hardcoded literals, and a paired device that had never signed in was the only
 * one telling the truth.
 */
@Composable
fun ServicesScreen(
    viewModel: ServicesViewModel,
    onSignUp: () -> Unit,
    onSignIn: () -> Unit,
    onGetMoreStorage: () -> Unit,
    onChangeIdentityMethod: () -> Unit,
    onDisconnect: () -> Unit,
    /** Opens the account-deletion flow (§9.7). Offered only on a device signed in with an email. */
    onDeleteAccount: (() -> Unit)?,
    // Store-mobile builds pass false: the Stripe billing portal is external payment too (it changes
    // plans and payment methods), so Play's Payments policy rules it out like the Subscribe CTA.
    showManageSubscription: Boolean,
) {
    // Coming back from the browser is when a purchase or a plan change shows up.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    val cardState by viewModel.cardState.collectAsStateWithLifecycle()
    val portalUrl by viewModel.portalUrl.collectAsStateWithLifecycle()
    val portalNotice by viewModel.portalNotice.collectAsStateWithLifecycle()
    val portalBusy by viewModel.portalBusy.collectAsStateWithLifecycle()

    val urlLauncher = rememberOpenExternalUrlLauncher()
    // One-shot: open the returned billing-portal URL, then clear it so a recomposition doesn't reopen.
    val pendingPortalUrl = portalUrl
    if (pendingPortalUrl != null) {
        LaunchedEffect(pendingPortalUrl) {
            urlLauncher.open(pendingPortalUrl)
            viewModel.onPortalUrlOpened()
        }
    }

    ServicesScreenContent(
        cardState = cardState,
        onSignUp = onSignUp,
        onSignIn = onSignIn,
        onGetMoreStorage = onGetMoreStorage,
        onChangeIdentityMethod = onChangeIdentityMethod,
        onDisconnect = onDisconnect,
        onDeleteAccount = onDeleteAccount,
        onManageSubscription = manageSubscriptionAction(
            showManageSubscription = showManageSubscription,
            canOpenBrowser = urlLauncher.canOpen,
            openPortal = viewModel::openBillingPortal,
        ),
        portalNotice = portalNotice,
        portalBusy = portalBusy,
    )
}

/**
 * The Manage subscription action, or null to hide the button: only where this build may link to
 * the billing portal and a browser can open it.
 */
internal fun manageSubscriptionAction(
    showManageSubscription: Boolean,
    canOpenBrowser: Boolean,
    openPortal: () -> Unit,
): (() -> Unit)? = if (showManageSubscription && canOpenBrowser) openPortal else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreenContent(
    cardState: ServicesCardState,
    onSignUp: () -> Unit,
    onGetMoreStorage: () -> Unit,
    onChangeIdentityMethod: () -> Unit,
    onDisconnect: () -> Unit,
    onSignIn: () -> Unit = {},
    onManageSubscription: (() -> Unit)? = null,
    onDeleteAccount: (() -> Unit)? = null,
    portalNotice: String? = null,
    portalBusy: Boolean = false,
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.services_title)) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().appContentWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Nothing to show only when there is neither funded storage nor a session here.
                if (cardState.storage == null && !cardState.signedInHere) {
                    TenshuRelayEmptyCard(onSignUp = onSignUp)
                } else {
                    TenshuRelayCard(
                        cardState = cardState,
                        onSignIn = onSignIn,
                        onGetMoreStorage = onGetMoreStorage,
                        onChangeIdentityMethod = onChangeIdentityMethod,
                        onDisconnect = onDisconnect,
                        onManageSubscription = onManageSubscription,
                        onDeleteAccount = onDeleteAccount,
                        portalNotice = portalNotice,
                        portalBusy = portalBusy,
                    )
                }
            }
        }
    }
}

@Composable
private fun TenshuRelayEmptyCard(onSignUp: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.services_relay_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.services_relay_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onSignUp) {
                Text(stringResource(Res.string.services_sign_up))
            }
        }
    }
}

/**
 * The single relay card. [ServicesCardState.storage] drives everything numeric;
 * [ServicesCardState.identity] decides whether this device may act on the account.
 */
@Composable
private fun TenshuRelayCard(
    cardState: ServicesCardState,
    onSignIn: () -> Unit,
    onGetMoreStorage: () -> Unit,
    onChangeIdentityMethod: () -> Unit,
    onDisconnect: () -> Unit,
    onManageSubscription: (() -> Unit)?,
    onDeleteAccount: (() -> Unit)?,
    portalNotice: String?,
    portalBusy: Boolean,
) {
    val storage = cardState.storage
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.services_relay_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (storage != null) {
                // The real plan + the real fill level, read off the synced entitlement — true on
                // every device, whether or not it holds a session (D11: descriptor, not credential).
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.services_plan_label, syncedPlanLabelText(storage.plan)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = storageLabelText(storage.usedBytes, storage.limitBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 8.dp, not 4: at 4 the bar sits close enough to the usage line to read as an
                // underline of it rather than as a gauge. Noticed by rendering the card.
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (storage.limitBytes <= 0) 0f
                        else (storage.usedBytes.toFloat() / storage.limitBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Signed in here, but no funded-storage figures. Still not a 0-byte gauge — that
                // reads as "you have no storage", the exact wrong impression right after a signup.
                //
                // ⚠ But it is no longer phrased as setup-in-progress either. The same state is
                // reached when the entitlement cannot be verified or the relay cannot be reached,
                // and "this usually takes a moment" then promises an outcome that never arrives —
                // a card sat on that sentence for nearly two hours on 2026-08-07 with a valid
                // entitlement in the store. The copy now describes the state and points at where
                // the durable reason lands when there is one.
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.services_storage_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.services_storage_pending_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val identity = cardState.identity
            if (identity != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = identityLabelText(identity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.services_synced_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (identity == null) {
                // The login is the visible boundary between "viewing my synced data" and "changing
                // my account" (D11): billing/identity actions appear only after a per-device
                // email sign-in. The figures above were never behind that boundary.
                Text(
                    text = stringResource(Res.string.services_synced_manage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.services_sign_in_to_manage))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGetMoreStorage, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.services_get_more_storage))
                    }
                    if (onManageSubscription != null) {
                        OutlinedButton(
                            onClick = onManageSubscription,
                            enabled = !portalBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.services_manage_subscription))
                        }
                        if (portalNotice != null) {
                            Text(
                                text = portalNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(onClick = onChangeIdentityMethod, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.services_change_identity))
                    }
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.services_disconnect))
                    }
                    // ⚠ Only for an email identity: a redeemed code (ReiKey) has no account and no
                    // session to delete. Destructive, so a text button in the error colour, last.
                    if (onDeleteAccount != null && identity is PublisherAccount.IdentityMethod.Email) {
                        TextButton(onClick = onDeleteAccount, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(Res.string.services_delete_account),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun syncedPlanLabelText(plan: SyncedPlanTier): String = stringResource(
    when (plan) {
        SyncedPlanTier.Free -> Res.string.services_plan_free
        SyncedPlanTier.Basic -> Res.string.services_plan_basic
        SyncedPlanTier.Premium -> Res.string.services_plan_premium
    }
)

@Composable
private fun identityLabelText(method: PublisherAccount.IdentityMethod): String = when (method) {
    is PublisherAccount.IdentityMethod.Email ->
        stringResource(Res.string.services_identity_email, method.address)
    is PublisherAccount.IdentityMethod.ReiKey ->
        stringResource(Res.string.services_identity_rei, method.source)
}

/*
 * ⚠ Through the platform-wide `formatStorageBytes` (`:storage-service-common`), which the iOS
 * Services screen and every app's storage banner share. Two traps it closes: Premium's 2 TiB must
 * read "2 TB", never "2048 GB" (the CE-6 defect, found by rendering the card); and the steps must
 * be binary like the plans, or Free's 5 GiB reads "5.37 GB" (the iOS screen, 2026-10-14).
 */
@Composable
private fun storageLabelText(usedBytes: Long, limitBytes: Long): String =
    stringResource(
        Res.string.services_storage_used,
        formatStorageBytes(usedBytes),
        formatStorageBytes(limitBytes),
    )
