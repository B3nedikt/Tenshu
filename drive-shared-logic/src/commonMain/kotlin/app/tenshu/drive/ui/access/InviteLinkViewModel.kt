package app.tenshu.drive.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.copyKey
import app.tenshu.error.ErrorCopyKey
import org.tenshu.contact.ContactInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for [InviteLinkSheet] (`docs/proposals/drive-manage-access-ux.md`
 * §2.2). The raw [link] exists only in this session (handshake doc §3):
 * dismissing the sheet discards it — *Invited* rows offer *Revoke*, never
 * *Show link again*.
 */
data class InviteLinkUiState(
    /** Advisory intent label — the folder name, or `""` outside Manage access. */
    val folderName: String,
    val isCreating: Boolean = false,
    /** The one-session messaging link, set once the op succeeds. */
    val link: String? = null,
    /** Why generating the link failed, as a catalogue key — never `RepositoryError.toString()`. */
    val errorCopyKey: ErrorCopyKey? = null,
)

/**
 * ViewModel for [InviteLinkSheet] — the `CreateShareLinkViewModel` pattern
 * (generate → result → hand out) over `createContactInvite`.
 */
class InviteLinkViewModel(
    private val contactsRepository: ContactsRepository,
    folderName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteLinkUiState(folderName = folderName))
    val uiState: StateFlow<InviteLinkUiState> get() = _uiState

    fun onGenerateClicked() {
        val state = _uiState.value
        if (state.isCreating || state.link != null) return
        _uiState.update { it.copy(isCreating = true, errorCopyKey = null) }
        viewModelScope.launch {
            val result = contactsRepository.createInvite(
                intentKind = if (state.folderName.isNotEmpty()) {
                    ContactInvite.IntentKind.SHARE_FOLDER
                } else {
                    ""
                },
                intentLabel = state.folderName,
            )
            when (result) {
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(isCreating = false, link = result.value, errorCopyKey = null)
                }
                is RepositoryResult.Error -> _uiState.update {
                    it.copy(isCreating = false, errorCopyKey = result.error.copyKey())
                }
            }
        }
    }
}
