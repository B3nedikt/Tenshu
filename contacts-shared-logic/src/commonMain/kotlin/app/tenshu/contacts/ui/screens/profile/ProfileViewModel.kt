package app.tenshu.contacts.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.model.OwnProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ⚠⚠ D12 (`ios-fit-timeline-maps-parity.md`): an umbrella export must not take a Swift
 * name off a shipped shell. `app.tenshu.fit.ui.screens.profile.ProfileUiState` is on the **same**
 * `TenshuSdk` umbrella, and K/N resolves an Obj-C name clash by appending `_` to whichever
 * fq name sorts LATER — without [kotlin.native.ObjCName], `app.tenshu.contacts.*` silently
 * renames **Fit's** type to `ProfileUiState_` and breaks `fit-ios`.
 *
 * The newcomer yields, as `ContactsRepository` / `RepositoryResult` do for the
 * `:drive-shared-logic` clash. No Kotlin gate catches a miss, because none builds Swift.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ContactsProfileUiState")
data class ProfileUiState(
    val profile: OwnProfile = OwnProfile(),
    val editing: Boolean = false,
    val draftName: String = "",
    val loaded: Boolean = false,
) {
    /** Blank names are not saveable — the name rides inside handshake payloads. */
    val canSave: Boolean get() = editing && draftName.isNotBlank()

    /** True when this device can show the identity fingerprint. See [OwnProfile]. */
    val hasFingerprint: Boolean get() = profile.fingerprint.isNotEmpty()
}

/**
 * The own profile card (`docs/proposals/contacts-app.md` D6) — a detail-shaped view of
 * yourself. The name is the platform-level `OwnerPreferences.Name`, the same row Drive's
 * handshake payloads and Fit's Profile read, so there is exactly one answer to "what is my
 * name".
 *
 * ⚠⚠ [kotlin.native.ObjCName] for the same D12 clash as [ProfileUiState]: without it, Fit's
 * `app.tenshu.fit.ui.screens.profile.ProfileViewModel` is exported as `ProfileViewModel_`.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ContactsProfileViewModel")
class ProfileViewModel(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.ownProfile().collect { profile ->
                _uiState.update { state ->
                    state.copy(
                        profile = profile,
                        loaded = true,
                        // Don't clobber what the user is typing. The flow re-emits on every
                        // change — including the one this screen just saved — so seeding the
                        // draft unconditionally would fight the keyboard.
                        draftName = if (state.editing) state.draftName else profile.displayName,
                    )
                }
            }
        }
    }

    fun startEditing() =
        _uiState.update { it.copy(editing = true, draftName = it.profile.displayName) }

    fun onDraftChange(name: String) = _uiState.update { it.copy(draftName = name) }

    fun cancelEditing() =
        _uiState.update { it.copy(editing = false, draftName = it.profile.displayName) }

    fun save() {
        val name = _uiState.value.draftName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.setOwnDisplayName(name)
            _uiState.update { it.copy(editing = false) }
        }
    }
}
