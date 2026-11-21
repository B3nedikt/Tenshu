package app.tenshu.drive.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A change this realm lost (CR-2 B4 P3, `content-plane-cr2-b4-spec.md` §2.4): [fileName] was deleted
 * on another device while a change to it waited, and no device held its bytes to put it back. Read
 * from the synced `ChangeLostNotification` rows; [id] is the notification's uuid.
 */
data class ChangeLostModel(val id: String, val fileName: String, val path: String)

/**
 * Which change-lost notices this run has dismissed — only so the banner goes the moment it is
 * dismissed, before the synced record round-trips. Process-wide rather than per screen, so a notice
 * dismissed in one folder stays dismissed in the next.
 *
 * ⚠ Not where a dismissal lives: that is the synced per-notice record [FilesRepository.dismissChangeLost]
 * writes, which survives restarts and reaches every device. Dismissing never deletes the notice
 * itself — Control's feed keeps showing it.
 */
object ChangeLostDismissals {
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    val ids: StateFlow<Set<String>> get() = dismissed

    fun dismiss(id: String) = dismissed.update { it + id }
}
