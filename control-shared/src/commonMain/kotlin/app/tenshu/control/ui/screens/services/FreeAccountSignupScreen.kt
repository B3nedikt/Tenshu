package app.tenshu.control.ui.screens.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import app.tenshu.control.shared.resources.free_signup_privacy_link
import app.tenshu.control.shared.resources.free_signup_privacy_notice
import app.tenshu.control.shared.resources.free_signup_terms_link
import app.tenshu.control.shared.resources.free_signup_terms_notice
import app.tenshu.ui.legal.rememberPrivacyPolicyOpener
import app.tenshu.ui.legal.rememberPrivacyPolicyUrl
import app.tenshu.ui.legal.rememberTermsOfUseOpener
import app.tenshu.ui.legal.rememberTermsOfUseUrl
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.free_signin_body
import app.tenshu.control.shared.resources.free_signin_heading
import app.tenshu.control.shared.resources.free_signin_title
import app.tenshu.control.shared.resources.free_signup_body
import app.tenshu.control.shared.resources.free_signup_cancel
import app.tenshu.control.shared.resources.free_signup_code_body
import app.tenshu.control.shared.resources.free_signup_code_heading
import app.tenshu.control.shared.resources.free_signup_code_label
import app.tenshu.control.shared.resources.free_signup_display_name_label
import app.tenshu.control.shared.resources.free_signup_edit_email
import app.tenshu.control.shared.resources.free_signup_email_label
import app.tenshu.control.shared.resources.free_signup_heading
import app.tenshu.control.shared.resources.free_signup_sending
import app.tenshu.control.shared.resources.free_signup_submit
import app.tenshu.control.shared.resources.free_signup_title
import app.tenshu.control.shared.resources.free_signup_verify
import app.tenshu.control.shared.resources.free_signup_resend
import app.tenshu.control.shared.resources.free_signup_resent
import app.tenshu.control.shared.resources.free_signup_verifying
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * §3.2 Free path per `docs/proposals/commerce-accounts-and-sessions.md`. Owns the email entry and
 * the **email-OTP** code entry (Phase 3b — the real verification, replacing the earlier poll).
 * Routes back to the caller when verification completes (or the user cancels).
 *
 * The same flow serves two framings (§3.5 / D11): [signIn] = false is *sign-up* (create the free
 * account) and [signIn] = true is *sign-in* (re-authenticate an existing account on **this**
 * device — a joiner, or a device that lost its local session). The mechanics are identical (the
 * `register` → `verifyOtp` email-OTP handshake binds a session either way); only the copy and the
 * optional display-name field differ, so the user understands they're not creating a second
 * account or paying again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeAccountSignupScreen(
    viewModel: FreeAccountSignupViewModel,
    onCancel: () -> Unit,
    onVerified: () -> Unit,
    signIn: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.step) {
        if (state.step == FreeAccountSignupStep.Done) onVerified()
    }

    val topTitle = if (signIn) Res.string.free_signin_title else Res.string.free_signup_title
    Scaffold(topBar = { AppTopAppBar(title = stringResource(topTitle)) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .widthIn(max = 480.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.step) {
                    FreeAccountSignupStep.Form -> FormStep(
                        email = state.email,
                        displayName = state.displayName,
                        busy = state.busy,
                        error = state.error,
                        signIn = signIn,
                        onEmailChange = viewModel::onEmailChange,
                        onDisplayNameChange = viewModel::onDisplayNameChange,
                        onSubmit = viewModel::onSubmit,
                        onCancel = onCancel,
                    )
                    FreeAccountSignupStep.EnterCode -> EnterCodeStep(
                        email = state.email,
                        code = state.code,
                        busy = state.busy,
                        error = state.error,
                        resent = state.resent,
                        onCodeChange = viewModel::onCodeChange,
                        onVerify = viewModel::onVerify,
                        onResend = viewModel::onResend,
                        onEditEmail = viewModel::onEditEmail,
                    )
                    FreeAccountSignupStep.Done -> CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun FormStep(
    email: String,
    displayName: String,
    busy: Boolean,
    error: String?,
    signIn: Boolean,
    onEmailChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(
            if (signIn) Res.string.free_signin_heading else Res.string.free_signup_heading,
        ),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(
            if (signIn) Res.string.free_signin_body else Res.string.free_signup_body,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(Res.string.free_signup_email_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    // The display name is only meaningful when creating an account; signing in on
    // another device re-uses the existing account, so hide it there.
    if (!signIn) {
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text(stringResource(Res.string.free_signup_display_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
    }
    // Above the button: the acceptance must be read before the tap that gives it.
    LegalNotice()
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onSubmit,
        enabled = !busy && email.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (busy) stringResource(Res.string.free_signup_sending)
            else stringResource(Res.string.free_signup_submit)
        )
    }
    TextButton(onClick = onCancel, enabled = !busy) {
        Text(stringResource(Res.string.free_signup_cancel))
    }
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Above the submit button: "By continuing you accept our terms of use and confirm that you are 16
 * or older. How we handle your data is explained in our privacy policy.", each page's name a link.
 * `register` sends `TOS_VERSION` and `PRIVACY_VERSION`, each the date of the page linked here.
 * ⚠⚠ Only the terms are accepted. The privacy half informs, it never asks for agreement: under the
 * GDPR a privacy policy is information, and the legal bases are Art. 6(1)(b)/(f), not consent.
 */
@Composable
private fun LegalNotice() {
    val termsLink = stringResource(Res.string.free_signup_terms_link)
    val terms = stringResource(Res.string.free_signup_terms_notice, termsLink)
    val privacyLink = stringResource(Res.string.free_signup_privacy_link)
    val privacy = stringResource(Res.string.free_signup_privacy_notice, privacyLink)
    val termsUrl = rememberTermsOfUseUrl()
    val openTerms = rememberTermsOfUseOpener()
    val privacyUrl = rememberPrivacyPolicyUrl()
    val openPrivacy = rememberPrivacyPolicyOpener()
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val text = remember(terms, termsLink, termsUrl, privacy, privacyLink, privacyUrl, linkStyle) {
        buildAnnotatedString {
            fun sentence(sentence: String, link: String, url: String, open: () -> Unit) {
                val offset = length
                append(sentence)
                val start = sentence.indexOf(link)
                if (start >= 0) {
                    addLink(LinkAnnotation.Url(url, linkStyle) { open() }, offset + start, offset + start + link.length)
                }
            }
            sentence(terms, termsLink, termsUrl, openTerms)
            append(' ')
            sentence(privacy, privacyLink, privacyUrl, openPrivacy)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EnterCodeStep(
    email: String,
    code: String,
    busy: Boolean,
    error: String?,
    resent: Boolean,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onEditEmail: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.free_signup_code_heading),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(Res.string.free_signup_code_body, email),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        label = { Text(stringResource(Res.string.free_signup_code_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onVerify,
        enabled = !busy && code.length == FreeAccountSignupViewModel.OTP_LENGTH,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (busy) stringResource(Res.string.free_signup_verifying)
            else stringResource(Res.string.free_signup_verify)
        )
    }
    // CE-4: the capability already existed via *Change email* → resubmit, but nothing named it, so
    // a user staring at an inbox with no code had no obvious next move. ⚠ The service budgets
    // resends (5/address/hour); over that the ordinary error surfaces.
    TextButton(onClick = onResend, enabled = !busy) {
        Text(stringResource(Res.string.free_signup_resend))
    }
    TextButton(onClick = onEditEmail, enabled = !busy) {
        Text(stringResource(Res.string.free_signup_edit_email))
    }
    if (resent && error == null) {
        Text(
            stringResource(Res.string.free_signup_resent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

