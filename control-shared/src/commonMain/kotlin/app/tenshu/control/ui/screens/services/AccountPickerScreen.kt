package app.tenshu.control.ui.screens.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.account_picker_basic_body
import app.tenshu.control.shared.resources.account_picker_basic_price
import app.tenshu.control.shared.resources.account_picker_basic_title
import app.tenshu.control.shared.resources.account_picker_body
import app.tenshu.control.shared.resources.account_picker_returning
import app.tenshu.control.shared.resources.account_picker_cancel
import app.tenshu.control.shared.resources.account_picker_free_body
import app.tenshu.control.shared.resources.account_picker_free_cta
import app.tenshu.control.shared.resources.account_picker_free_title
import app.tenshu.control.shared.resources.account_picker_have_code
import app.tenshu.control.shared.resources.account_picker_heading
import app.tenshu.control.shared.resources.account_picker_premium_body
import app.tenshu.control.shared.resources.account_picker_premium_price
import app.tenshu.control.shared.resources.account_picker_premium_title
import app.tenshu.control.shared.resources.account_picker_recommended_badge
import app.tenshu.control.shared.resources.account_picker_subscribe_cta
import app.tenshu.control.shared.resources.account_picker_title
import app.tenshu.control.data.CommerceProducts
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * The plan picker per `docs/proposals/commerce-accounts-and-sessions.md` §2.2.
 * A vertical stack of three plan cards — Free, Basic, Premium — reached from
 * `[Set up sync]` on the home recovery banner + `[Get more storage]` on
 * Services + a future pairing attempt that fails because there's no relay
 * account.
 *
 * The Free card verifies an email; the paid cards ([onSubscribe] with the tier's
 * [CommerceProducts] id) open Stripe hosted checkout (proposal Phase 4). The
 * low-emphasis "Have a code?" link below the cards is the advanced redemption
 * entry (§2.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerScreen(
    onPickFree: () -> Unit,
    onSubscribe: (productId: String) -> Unit,
    onHaveCode: () -> Unit,
    onBack: () -> Unit,
    // Store-mobile builds ship with this false (app-store steering policy — see [inAppCheckoutEnabled]):
    // the paid tiers' in-app Subscribe CTA is dropped; the account still reflects any tier bought on the
    // web. Defaults true (desktop / sideload / tests).
    showPaidTiers: Boolean = true,
) {
    Scaffold(
        topBar = { AppTopAppBar(title = stringResource(Res.string.account_picker_title)) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.account_picker_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.account_picker_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // The picker is also the way back in for a returning user (§3.7): Free routes to
                // the same email-OTP screen and `bindOwner` restores their real plan.
                Text(
                    text = stringResource(Res.string.account_picker_returning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FreeCard(onPick = onPickFree)
                if (showPaidTiers) {
                    PaidCard(
                        title = stringResource(Res.string.account_picker_basic_title),
                        body = stringResource(Res.string.account_picker_basic_body),
                        price = stringResource(Res.string.account_picker_basic_price),
                        onSubscribe = { onSubscribe(CommerceProducts.BASIC) },
                    )
                    PaidCard(
                        title = stringResource(Res.string.account_picker_premium_title),
                        body = stringResource(Res.string.account_picker_premium_body),
                        price = stringResource(Res.string.account_picker_premium_price),
                        onSubscribe = { onSubscribe(CommerceProducts.PREMIUM) },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onHaveCode) {
                    Text(stringResource(Res.string.account_picker_have_code))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(Res.string.account_picker_cancel))
                }
            }
        }
    }
}

@Composable
private fun FreeCard(onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.account_picker_free_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                RecommendedBadge()
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.account_picker_free_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.account_picker_free_cta))
            }
        }
    }
}

/**
 * A paid tier (Basic / Premium). `[Subscribe]` opens Stripe hosted checkout for the tier
 * (proposal Phase 4) via [onSubscribe].
 */
@Composable
private fun PaidCard(title: String, body: String, price: String, onSubscribe: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = price,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSubscribe,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.account_picker_subscribe_cta))
            }
        }
    }
}

@Composable
private fun RecommendedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(Res.string.account_picker_recommended_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
