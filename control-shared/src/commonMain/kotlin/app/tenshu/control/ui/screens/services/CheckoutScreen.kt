package app.tenshu.control.ui.screens.services

import app.tenshu.control.shared.resources.checkout_store_confirming

import app.tenshu.control.shared.resources.checkout_store_launching

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.checkout_ack_body
import app.tenshu.control.shared.resources.checkout_ack_checkbox
import app.tenshu.control.shared.resources.checkout_ack_continue
import app.tenshu.control.shared.resources.checkout_ack_heading
import app.tenshu.control.shared.resources.checkout_ack_terms
import app.tenshu.control.shared.resources.checkout_ack_withdrawal_link
import app.tenshu.ui.legal.rememberTermsWithdrawalOpener
import app.tenshu.control.shared.resources.checkout_awaiting_body
import app.tenshu.control.shared.resources.checkout_awaiting_heading
import app.tenshu.control.shared.resources.checkout_cancel
import app.tenshu.control.shared.resources.checkout_done_body
import app.tenshu.control.shared.resources.checkout_done_cta
import app.tenshu.control.shared.resources.checkout_done_heading
import app.tenshu.control.shared.resources.checkout_error_heading
import app.tenshu.control.shared.resources.checkout_launching
import app.tenshu.control.shared.resources.checkout_reopen
import app.tenshu.control.shared.resources.checkout_retry
import app.tenshu.control.shared.resources.checkout_title
import app.tenshu.control.ui.common.rememberOpenExternalUrlLauncher
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * Paid-tier checkout (`commerce-accounts-and-sessions.md` §6, §9.5). On [CheckoutStep.Launching] it
 * asks the service for a hosted Stripe URL and opens it in the system browser; the actual grant is
 * confirmed by the synced entitlement, not the browser return (the VM watches the synced
 * `StorageStatus`). [onDone] fires once the tier is active; [onCancel] backs out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    viewModel: CheckoutViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /** The foreground UI a store's sheet opens from; a parameter so a test can supply one. */
    storeHost: Any? = rememberStorePurchaseHost(),
) {
    // Coming back from the browser is when a purchase or a plan change shows up.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberOpenExternalUrlLauncher()

    // One-shot: open the hosted checkout URL when the VM emits one, then clear it so a recomposition
    // doesn't reopen the browser.
    val launchUrl = state.launchUrl
    if (launchUrl != null) {
        androidx.compose.runtime.LaunchedEffect(launchUrl) {
            launcher.open(launchUrl)
            viewModel.onLaunchUrlOpened()
        }
    }

    // One-shot: a store purchase (a Play build) is ready — hand the VM this screen's host and let
    // it run the sheet. ⚠⚠ Only the hand-over happens here: the purchase itself runs in the VM's
    // scope, because consuming the one-shot recomposes this screen, and a purchase run in this
    // effect was cancelled by that very recomposition (or by a rotation). Unconditional on purpose,
    // for the same reason: an effect inside `if (storePurchase != null)` leaves composition with it.
    val storePurchase = state.storePurchase
    androidx.compose.runtime.LaunchedEffect(storePurchase) {
        if (storePurchase != null) viewModel.launchStorePurchase(storeHost)
    }

    CheckoutScreenContent(
        state = state,
        canReopen = launcher.canOpen,
        onAcknowledgementChanged = viewModel::onAcknowledgementChanged,
        onConfirmAcknowledgement = viewModel::confirmAcknowledgement,
        onReopen = viewModel::reopenCheckout,
        onRetry = viewModel::start,
        onDone = onDone,
        onCancel = onCancel,
    )
}

/**
 * The stateless body, split out on the [ServicesScreenContent] precedent so each step can be
 * rendered — and looked at — without a ViewModel or a Main dispatcher (see
 * `CheckoutScreenRenderTest`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreenContent(
    state: CheckoutUiState,
    canReopen: Boolean,
    onAcknowledgementChanged: (Boolean) -> Unit,
    onConfirmAcknowledgement: () -> Unit,
    onReopen: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { AppTopAppBar(title = stringResource(Res.string.checkout_title)) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .widthIn(max = 480.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.step) {
                    // Policy §5.4: the tick is the customer's express request to start before the
                    // withdrawal period ends, so a withdrawal owes pro-rata value (§ 357a Abs. 2
                    // BGB) — it does NOT extinguish the right (a storage plan is a digital service).
                    // The linked notice is what starts the period (§ 356 Abs. 3). ⚠ The tick is not a
                    // formality: `/commerce/checkout` refuses a purchase without it, so this screen
                    // cannot be skipped past by a client that forgets to ask.
                    CheckoutStep.Acknowledge -> {
                        CenteredText(
                            stringResource(Res.string.checkout_ack_heading),
                            MaterialTheme.typography.titleMedium,
                        )
                        CenteredText(
                            stringResource(Res.string.checkout_ack_body),
                            MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // The whole row toggles, not just the box: a 20 dp hit target for
                                // the one control standing between a customer and a charge is bad
                                // manners, and on touch it reads as unresponsive.
                                .toggleable(
                                    value = state.acknowledged,
                                    onValueChange = onAcknowledgementChanged,
                                    role = Role.Checkbox,
                                ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Checkbox(checked = state.acknowledged, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.checkout_ack_checkbox),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val openWithdrawal = rememberTermsWithdrawalOpener()
                        TextButton(onClick = openWithdrawal) {
                            Text(stringResource(Res.string.checkout_ack_withdrawal_link))
                        }
                        CenteredText(
                            stringResource(Res.string.checkout_ack_terms),
                            MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onConfirmAcknowledgement,
                            enabled = state.acknowledged,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.checkout_ack_continue))
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(Res.string.checkout_cancel))
                        }
                    }

                    CheckoutStep.Launching -> {
                        CircularProgressIndicator()
                        CenteredText(
                            stringResource(if (state.viaStore) Res.string.checkout_store_launching else Res.string.checkout_launching),
                            MaterialTheme.typography.bodyLarge,
                        )
                    }

                    // The store's own sheet is on screen above this one.
                    CheckoutStep.AwaitingStore -> {
                        CircularProgressIndicator()
                        CenteredText(stringResource(Res.string.checkout_store_launching), MaterialTheme.typography.bodyLarge)
                    }

                    // Paid in the store; commerce has it and the plan is on its way to this device.
                    CheckoutStep.AwaitingConfirmation if state.viaStore -> {
                        CircularProgressIndicator()
                        CenteredText(stringResource(Res.string.checkout_store_confirming), MaterialTheme.typography.bodyLarge)
                    }

                    CheckoutStep.AwaitingConfirmation -> {
                        CircularProgressIndicator()
                        CenteredText(
                            stringResource(Res.string.checkout_awaiting_heading),
                            MaterialTheme.typography.titleMedium,
                        )
                        CenteredText(
                            stringResource(Res.string.checkout_awaiting_body),
                            MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (canReopen) {
                            OutlinedButton(
                                onClick = onReopen,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.checkout_reopen))
                            }
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(Res.string.checkout_cancel))
                        }
                    }

                    CheckoutStep.Done -> {
                        CenteredText(
                            stringResource(Res.string.checkout_done_heading),
                            MaterialTheme.typography.titleMedium,
                        )
                        CenteredText(
                            stringResource(Res.string.checkout_done_body),
                            MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.checkout_done_cta))
                        }
                    }

                    CheckoutStep.Error -> {
                        CenteredText(
                            stringResource(Res.string.checkout_error_heading),
                            MaterialTheme.typography.titleMedium,
                        )
                        state.error?.let {
                            CenteredText(
                                it,
                                MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.checkout_retry))
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(Res.string.checkout_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = text, style = style, color = color, textAlign = TextAlign.Center)
}

