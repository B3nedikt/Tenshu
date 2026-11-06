package app.tenshu.drive.ui.contacts

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.data.RedeemOutcome
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.UnsupportedInviteVersionException

/**
 * The redeem flow's stages (`docs/proposals/drive-manage-access-ux.md` §3.2):
 * paste/confirm → name entry → waiting → terminal. [PasteError] keeps the two
 * typed parse failures apart (malformed vs needs-a-newer-Tenshu).
 */
sealed interface RedeemStage {
    /** Paste entry (the link came in by hand, not a deep link). */
    data object EnterLink : RedeemStage

    /** Parsed: show the inviter fingerprint + the self-asserted-name input. */
    data object ConfirmName : RedeemStage

    /** Redeemed; the ack wait is live (resumable across restarts). */
    data object Waiting : RedeemStage

    /** Mutual close — the inviter accepted; [ack] feeds the success card. */
    data class Accepted(val ack: InviteAckModel) : RedeemStage

    data object Rejected : RedeemStage

    /** Expired / revoked / **already used** — the T-CI-01 loud copy. */
    data object Gone : RedeemStage

    /** Ack failed the end-to-end checks (tamper-safe generic copy). */
    data object Invalid : RedeemStage
}

enum class PasteError { MALFORMED, UNSUPPORTED_VERSION }

data class RedeemInviteUiState(
    val stage: RedeemStage = RedeemStage.EnterLink,
    val pastedLink: String = "",
    val pasteError: PasteError? = null,
    /** Display-form fingerprint of the link-pinned inviter key. */
    val inviterFingerprint: String = "",
    /** "Your name as they'll see it" (≤ 64 chars, shared with the inviter). */
    val selfName: String = "",
    /** Proxy-derived §5.3 SAS code, available once redeemed (`""` before). */
    val sas: String = "",
    /** §5.3 *Codes match* — flows into `sasConfirmed` only on explicit check. */
    val sasConfirmed: Boolean = false,
    val isSubmitting: Boolean = false,
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
)

/**
 * ViewModel for [RedeemInviteScreen] (`drive-manage-access-ux.md` §3). The
 * link parses **client-side** first ([InviteLinkPayload.parse]) for typed
 * entry errors; the redeem op re-parses proxy-side. After redeeming, the ack
 * wait re-invokes on every `PENDING` budget elapse while this VM lives —
 * restarts resume through `listPendingRedemptions` (the Shared screen's
 * §3.2.4 path), so closing here loses nothing.
 */
class RedeemInviteViewModel(
    private val contactsRepository: ContactsRepository,
    initialLink: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RedeemInviteUiState())
    val uiState: StateFlow<RedeemInviteUiState> get() = _uiState

    private var parsedLink: String? = null
    private var selfNamePrefilled = false
    private var activeInviteId: String? = null
    private var waitJob: Job? = null

    init {
        if (initialLink != null) {
            onLinkEntered(initialLink)
        }
        viewModelScope.launch {
            contactsRepository.ownDisplayName()
                .catch { /* keep last-known */ }
                .collect { name ->
                    if (!selfNamePrefilled && name.isNotEmpty()) {
                        selfNamePrefilled = true
                        _uiState.update { it.copy(selfName = name) }
                    }
                }
        }
    }

    fun onPastedLinkChanged(value: String) {
        _uiState.update { it.copy(pastedLink = value, pasteError = null) }
    }

    fun onLinkEntered(link: String = _uiState.value.pastedLink) {
        val payload = try {
            InviteLinkPayload.parse(link)
        } catch (e: UnsupportedInviteVersionException) {
            _uiState.update {
                it.copy(pastedLink = link, pasteError = PasteError.UNSUPPORTED_VERSION)
            }
            return
        } catch (t: RuntimeException) {
            _uiState.update { it.copy(pastedLink = link, pasteError = PasteError.MALFORMED) }
            return
        }
        parsedLink = link
        _uiState.update {
            it.copy(
                stage = RedeemStage.ConfirmName,
                pastedLink = link,
                pasteError = null,
                inviterFingerprint = displayFingerprint(
                    org.tenshu.contact.common.identityFingerprint(payload.inviterPublicKey)
                ),
            )
        }
    }

    fun onSelfNameChanged(value: String) {
        selfNamePrefilled = true
        _uiState.update { it.copy(selfName = value.take(MAX_NAME_LENGTH)) }
    }

    fun onConfirmClicked() {
        val link = parsedLink ?: return
        val state = _uiState.value
        if (state.isSubmitting || state.selfName.isBlank()) return
        _uiState.update { it.copy(isSubmitting = true, errorCopyKey = null) }
        viewModelScope.launch {
            if (state.selfName.isNotEmpty()) {
                contactsRepository.setOwnDisplayName(state.selfName)
            }
            when (val outcome = contactsRepository.redeemInvite(link, state.selfName)) {
                is RedeemOutcome.Redeemed -> {
                    activeInviteId = outcome.inviteId
                    _uiState.update { it.copy(isSubmitting = false, stage = RedeemStage.Waiting) }
                    loadSas(outcome.inviteId)
                    startWait(outcome.inviteId)
                }
                // The single response slot was taken — T-CI-01's loud copy.
                RedeemOutcome.AlreadyUsed, RedeemOutcome.Gone ->
                    _uiState.update { it.copy(isSubmitting = false, stage = RedeemStage.Gone) }
                is RedeemOutcome.Failed ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorCopyKey = outcome.copyKey)
                    }
            }
        }
    }

    /** Resume an existing redemption (deep-linked from the Shared screen). */
    fun resumeWait(inviteId: String) {
        activeInviteId = inviteId
        _uiState.update { it.copy(stage = RedeemStage.Waiting) }
        loadSas(inviteId)
        startWait(inviteId)
    }

    /**
     * §5.3: the explicit *Codes match* check. While the wait is live the loop
     * is restarted so the (re)invocation carries `sasConfirmed` — the ACCEPTED
     * close then mints the inviter identity `VERIFIED`/`QR_SAS`. (If the ack
     * already landed unverified, the upgrade path is the v2 re-verification
     * surface — out of scope here.)
     */
    fun onSasConfirmedChanged(confirmed: Boolean) {
        _uiState.update { it.copy(sasConfirmed = confirmed) }
        val inviteId = activeInviteId ?: return
        if (_uiState.value.stage == RedeemStage.Waiting) {
            startWait(inviteId)
        }
    }

    private fun loadSas(inviteId: String) {
        viewModelScope.launch {
            val result = contactsRepository.pendingRedemptions()
            val row = (result as? RepositoryResult.Success)?.value
                ?.firstOrNull { it.inviteId == inviteId } ?: return@launch
            _uiState.update { it.copy(sas = row.sas) }
        }
    }

    private fun startWait(inviteId: String) {
        waitJob?.cancel()
        waitJob = viewModelScope.launch { awaitAck(inviteId) }
    }

    private suspend fun awaitAck(inviteId: String) {
        while (true) {
            val result = contactsRepository.awaitInviteAck(inviteId, _uiState.value.sasConfirmed)
            val ack = (result as? RepositoryResult.Success)?.value
                ?: return // transport failure — the redemption survives; resume later
            when (ack.status) {
                AwaitInviteAck.Result.PENDING -> continue // next slow-poll budget
                AwaitInviteAck.Result.ACCEPTED ->
                    _uiState.update { it.copy(stage = RedeemStage.Accepted(ack)) }
                AwaitInviteAck.Result.REJECTED ->
                    _uiState.update { it.copy(stage = RedeemStage.Rejected) }
                AwaitInviteAck.Result.GONE ->
                    _uiState.update { it.copy(stage = RedeemStage.Gone) }
                else ->
                    _uiState.update { it.copy(stage = RedeemStage.Invalid) }
            }
            return
        }
    }

    private companion object {
        /** Handshake doc §5: self-asserted names cap at 64 chars. */
        const val MAX_NAME_LENGTH = 64
    }
}
