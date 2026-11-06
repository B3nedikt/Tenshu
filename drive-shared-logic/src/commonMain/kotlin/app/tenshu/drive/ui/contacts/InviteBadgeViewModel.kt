package app.tenshu.drive.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/**
 * Feeds the Shared destination's nav badge (`drive-manage-access-ux.md`
 * §2.3): lit while any `ContactInvite` sits at `REDEEMED` — synced state, so
 * any device shows it. Lives at the App level because the Shared screen's own
 * VM only exists while that tab is open.
 */
class InviteBadgeViewModel(
    contactsRepository: ContactsRepository,
) : ViewModel() {

    val showBadge: StateFlow<Boolean> =
        contactsRepository.hasRedeemedInvites()
            .catch {
                // ⚠ `catch` does not exempt cancellation, and "no invites" is a claim, not an
                // absence of one — a leaving screen must not make it.
                if (it is CancellationException) throw it
                emit(false)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
