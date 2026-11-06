package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable

/**
 * A recorder capability, each gated by its own set of runtime permissions and requested **at the
 * point of use** (`docs/proposals/fit-app.md` §9.8):
 *
 *  - [Motion] — location (route) + activity recognition (steps) + the ongoing-recording
 *    notification. Requested when the user taps **Start**, the moment a workout begins.
 *  - [HeartRate] — Bluetooth (scan + connect) for a BLE chest strap. Requested **only** when the
 *    user opts to connect a heart-rate monitor, so the majority who own no strap are never asked
 *    for Bluetooth.
 *
 * Splitting the request per capability (rather than one batched grant on Record-screen entry, the
 * pre-§9.8-refinement behaviour) is what avoids the four-cold-dialogs ambush — each capability's
 * OS dialog is preceded by an in-app rationale ([RecorderPermissionPrimer]) and fires only when
 * its feature is actually invoked. Mirrors how Google Fit primes + defers Bluetooth/location.
 */
enum class RecorderCapability { Motion, HeartRate }

/**
 * The recorder runtime-permission gate (`docs/proposals/fit-app.md` §9.8) — the platform seam for
 * the **just-in-time, per-capability** permission request.
 *
 * `:fit-shared-ui` is android + desktop only, so this is a plain `expect`/`actual` (no iOS actual
 * needed). The desktop actual reports every capability granted (desktop has no runtime-permission
 * model), so the UI's priming + request flow collapses to an immediate proceed there.
 */
interface RecorderPermissionGate {

    /**
     * Whether [capability]'s **critical** permission is already held — so the caller can skip both
     * the priming card and the OS dialog and proceed straight to the feature. Always `true` on a
     * platform with no runtime permissions (desktop). The critical permission is location for
     * [RecorderCapability.Motion] and Bluetooth-connect for [RecorderCapability.HeartRate]; the
     * rest of a capability's set degrades gracefully (a denied notification/step grant simply drops
     * that affordance) so it does not gate re-prompting.
     */
    fun isGranted(capability: RecorderCapability): Boolean

    /**
     * Requests [capability]'s permissions. [onResult] fires once the user has responded, with
     * whether the **critical** permission ended up held. Safe to call when already granted: it
     * fires immediately with `true` and shows no dialog. Callers should proceed regardless of the
     * result — the engine degrades per stream — and use the flag only for messaging.
     */
    fun request(capability: RecorderCapability, onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberRecorderPermissionGate(): RecorderPermissionGate
