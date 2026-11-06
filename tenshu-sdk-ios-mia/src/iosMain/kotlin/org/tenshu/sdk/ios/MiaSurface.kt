package org.tenshu.sdk.ios

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.NotConnectedConversationRepository
import app.tenshu.mia.ui.screens.chat.ChatViewModel
import app.tenshu.mia.ui.screens.history.MiaHistoryViewModel

/**
 * Wires Mia's view-model layer (`:mia-shared-logic`) to iOS so the SwiftUI `mia-ios` app
 * resolves each screen's view model and observes its `StateFlow` through SKIE — the Mia
 * twin of [CalendarSurface] / [ContactsSurface] (home-and-mia-apps.md §8, M4).
 *
 * The runtime default is the honest **not-connected** repository
 * ([NotConnectedConversationRepository], in `commonMain` so it is unit-tested rather than
 * iOS-only). [MiaLocalRuntime] swaps in the real one — `TenshuConversationRepository` over the
 * in-process route-1 client — once Control has provisioned this app.
 *
 * ⚠⚠ **No mock is reachable from here.** This object bound `MockConversationRepository` until
 * 2026-10-02, on the reasoning that Mia had no backend on any platform; it has had one since
 * MQ-1, and the mock invents photos, places and a device acknowledgement that read as the
 * user's own. That is the shape `NotConnectedTimelineRepository` was written to undo. Mia has no
 * `DEV_HARNESS` scheme and no render probe, so nothing needs a mock installer — add one beside
 * the probe that wants it, not ahead of it.
 *
 * ⚠ View models are built **after** [installRepository]: each factory reads the repository at
 * call time, so a view model made earlier keeps the not-connected one for its whole life. The
 * shipping root guarantees the order by not building the shell until provisioning is known
 * (`ControlSetUpGate`, ADR 0022 §Decision 6).
 *
 * One repository instance backs both view models, so the Chat thread and History observe the
 * same conversation — mirroring the single Koin binding on the other platforms.
 */
object MiaSurface {

    private var installed: ConversationRepository = NotConnectedConversationRepository()

    /**
     * Install the repository subsequently-built view models bind. Called by the tier that owns a
     * live client — today only [MiaLocalRuntime] (route 1).
     */
    fun installRepository(repository: ConversationRepository) {
        installed = repository
    }

    /** Restore the honest **not-connected** surface — the teardown / fallback target. */
    fun installNotConnected() {
        installed = NotConnectedConversationRepository()
    }

    /**
     * The chat screen's view model.
     *
     * ⚠ No `VoiceInput` and no `TargetOpener`: this slice has no on-device recogniser wired and
     * citation taps are out of scope, so the mic stays hidden and a citation stays inert rather
     * than either failing on use (`ChatViewModel` treats both as optional for exactly this).
     */
    fun chatViewModel(): ChatViewModel = ChatViewModel(installed)

    fun historyViewModel(): MiaHistoryViewModel = MiaHistoryViewModel(installed)
}
