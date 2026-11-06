package app.tenshu.contacts.ui.screens.merge

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.MergeCandidate
import app.tenshu.contacts.model.findMergeCandidates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pair the user opened, with which side survives. */
data class MergeReview(
    val candidate: MergeCandidate,
    /**
     * The contact that survives; the other is folded into it and deleted.
     *
     * Defaults to the one that looks more like a real record — see [preferredTarget]. The
     * user can swap it, which is the *only* choice the confirmation asks for, because it is
     * the only one that changes the outcome: the target's petname is what remains.
     */
    val targetPersonId: String,
) {
    val target: Contact
        get() = if (candidate.first.personId == targetPersonId) candidate.first else candidate.second

    val source: Contact
        get() = if (candidate.first.personId == targetPersonId) candidate.second else candidate.first
}

data class MergeUiState(
    val candidates: List<MergeCandidate> = emptyList(),
    val reviewing: MergeReview? = null,
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
) {
    val hasCandidates: Boolean get() = candidates.isNotEmpty()
}

/**
 * The duplicate-review flow (`docs/proposals/contacts-app.md` D7).
 *
 * ⚠⚠ **Suggests, never acts.** Candidates are recomputed from the contact list on every
 * emission and nothing is written until the user confirms a specific pair (D7's *never
 * auto-merge*, see `MergePersons`).
 *
 * Derived rather than stored, which also means a merge made on another device simply makes
 * the pair stop being suggested here — no dismissal state to sync, and no stale list.
 */
class MergeViewModel(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeUiState())
    val uiState: StateFlow<MergeUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.contacts().collect { contacts ->
                val candidates = findMergeCandidates(contacts)
                _uiState.update { state ->
                    state.copy(
                        candidates = candidates,
                        loaded = true,
                        // A pair whose contacts changed underneath (merged elsewhere, one of
                        // them deleted) stops being reviewable — keeping the sheet open over
                        // a stale pair is how a merge ends up hitting a contact that no
                        // longer exists.
                        reviewing = state.reviewing?.takeIf { review ->
                            candidates.any { it.samePairAs(review.candidate) }
                        },
                    )
                }
            }
        }
    }

    fun onCandidateClicked(candidate: MergeCandidate) = _uiState.update {
        it.copy(reviewing = MergeReview(candidate, preferredTarget(candidate)), errorCopyKey = null)
    }

    fun onReviewDismissed() = _uiState.update { it.copy(reviewing = null) }

    /** Swap which side survives. */
    fun onTargetChanged(personId: String) = _uiState.update { state ->
        state.copy(reviewing = state.reviewing?.copy(targetPersonId = personId))
    }

    fun onMergeConfirmed() {
        val review = _uiState.value.reviewing ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            val result = repository.mergeContacts(
                sourcePersonId = review.source.personId,
                targetPersonId = review.target.personId,
            )
            when (result) {
                is RepositoryResult.Success ->
                    // The candidate list re-derives from the repository, so the pair
                    // disappears because it stopped being a pair — not because anything
                    // here remembered dismissing it.
                    _uiState.update { it.copy(isSaving = false, reviewing = null) }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSaving = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    fun onErrorDismissed() = _uiState.update { it.copy(errorCopyKey = null) }
}

/**
 * Which side to keep by default: whichever holds more of what a user would miss.
 *
 * A connected contact outranks an import-only one — losing its keyring's petname would be the
 * more surprising outcome — and otherwise the one with more detail wins. It is only a default;
 * the confirmation puts both names in front of the user precisely because this cannot be
 * decided for them.
 */
internal fun preferredTarget(candidate: MergeCandidate): String {
    val a = candidate.first
    val b = candidate.second
    val score = { c: Contact ->
        (if (c.isConnected) 100 else 0) +
            c.identities.size * 10 +
            c.details.phones.size + c.details.emails.size +
            (if (c.avatar != null) 1 else 0)
    }
    return if (score(b) > score(a)) b.personId else a.personId
}

/** Same two people, whichever order they arrived in. */
internal fun MergeCandidate.samePairAs(other: MergeCandidate): Boolean {
    val mine = setOf(first.personId, second.personId)
    return mine == setOf(other.first.personId, other.second.personId)
}
