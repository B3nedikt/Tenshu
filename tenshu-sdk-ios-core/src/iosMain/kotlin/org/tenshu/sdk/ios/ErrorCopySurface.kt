package org.tenshu.sdk.ios

import app.tenshu.error.ErrorCopyKey
import org.tenshu.core.TError
import app.tenshu.error.errorCopyKey
import app.tenshu.error.errorCopyKeyForId

/**
 * The iOS half of the error copy catalogue — `docs/proposals/core-error-copy-catalogue.md` §5.5.
 *
 * ⚠⚠ **This is a second renderer, not a duplicate to delete.** Route-1 iOS apps are SwiftUI over
 * this bridge and never link Compose, so `:ui-core`'s `strings.xml` cannot serve them — the same
 * reason `RealmStorageState` lives in `:storage-service-common` rather than `:ui-core`. What is
 * shared is the **key**, derived from the error's schema id in `:core`; what is duplicated is only
 * the key → copy table, which is exactly the duplication ADR 0016 accepts at n=1 ("English-text
 * duplication is accepted") and anchor #8's n=2 codegen is meant to merge.
 *
 * ⚠ So the entries below must stay in step with
 * `ui-core/src/commonMain/composeResources/values/strings.xml`. Nothing enforces that; it is
 * the honest cost of having three renderers, and it is why the copy lives in one table per
 * renderer rather than scattered across the screens.
 *
 * §5.0 is what this file serves: no proxy code creates a localised string. `ControlSurface`'s
 * existing `*ErrorMessage` functions render typed carriers the same way — this one renders the
 * `TError` taxonomy itself, so a screen never has to reach for `GenericError.message`.
 */
object ErrorCopySurface {

    /**
     * The user-facing copy for a typed error.
     *
     * ⚠⚠ A catalogue miss returns the generic sentence, **never** the error's own `message` — the
     * proxy's English is diagnostics and belongs in the log, not on a phone.
     */
    fun label(error: TError): String = labelForKey(error.errorCopyKey())

    /** As [label], addressed by the error's schema id — for callers holding an id, not an error. */
    fun labelForId(errorTypeId: String): String = labelForKey(errorCopyKeyForId(errorTypeId))

    /**
     * The catalogue, keyed by the derivation in `:core`. Mirrors `:ui-core`'s `strings.xml`.
     *
     * ⚠ Not localised: iOS ships English-only here, as everything in this track does (§8, "Not a
     * second locale"). The value is the seam — when a second locale arrives this `when` becomes a
     * lookup into a generated catalogue keyed by exactly these names.
     */
    fun labelForKey(key: ErrorCopyKey): String = when (key.value) {
        "error_control_not_running" -> "Tenshu Control is not running."
        "error_dispatch_missing_parameter" -> "Something went wrong. Please try again."
        "error_dispatch_unknown_parameter" -> "Something went wrong. Please try again."
        "error_dispatch_wrong_parameter" -> "Something went wrong. Please try again."
        "error_operation_invalid_parameter" -> "Something went wrong. Please try again."
        "error_internal" -> "Something went wrong. Please try again."
        "error_generic" -> "Something went wrong. Please try again."
        "error_not_found" -> "That item is no longer available."
        "error_invalid_caller" -> "This app is not allowed to do that."
        "error_open_target_unavailable" -> "The app that owns this item is not available."
        "error_unannounced_session" -> "Tenshu needs to reconnect. Please try again."
        "error_unsupported_operation" -> "This version does not support that yet."
        "error_entitlement_unverified" -> "Your plan could not be verified. Please try again."
        "error_relay_route_unsupported" -> "Your other device needs an update to do this."
        "error_recovery_phrase_unreadable" -> "This device can't read its saved recovery phrase, so unlocking can't succeed. Use “Add This Device” from another device that is still signed in."
        "error_device_already_set_up" -> "This device is already set up with a different realm. To recover another realm here, first use “Forget this device”, then try again."
        "error_semantic_search_unavailable" -> "Smart search is not available on this device."
        "error_face_groups_unavailable" -> "People is not available on this device."
        "error_model_unavailable" -> "This model isn’t available on this device."
        "error_index_pass_refused" -> "Photo indexing couldn’t start on this device."
        "error_already_exists" -> "An item with that name already exists."
        "error_insufficient" -> "There is not enough storage left."
        "error_no_storage" -> "This realm has no storage yet."
        "error_metered_network_refused" -> "Not downloaded — you are on a metered connection."
        // ⚠ Deliberately not "no longer available": an absent manifest is equally a reclaimed
        // content, an upload in flight, an unpushed peer or a transient failure, and three of
        // those come back (`drive-upload-stop-and-reclamation.md` §4.19).
        // ⚠ Says WHY rather than refusing blankly: an unsent upload's bytes are its only copy, so
        // removing the download would lose it.
        "error_content_pinned" ->
            "This file is still uploading. Removing its download would take the only copy left to finish with."
        "error_content_unavailable" ->
            "Couldn't load this file's contents. They may still be syncing."
        // `content-plane-cr2-b5-spec.md` §4: a provisional slice-snapshot row cannot be moved until
        // the log reaches it.
        "error_still_syncing" -> "This item is still syncing to this device. Try again shortly."
        // `content-plane-cr2-b6-spec.md` D3: an offline move of content this device does not hold is refused.
        "error_move_needs_local_bytes" ->
            "You're offline and this item isn't downloaded. Download it first, or move it once you're online."
        // ⚠ Minted by the repositories that call `CompleteContactHandshake` (threat model
        // T-CI-10) — see `ErrorCopyKey.INVITE_RESPONSE_CHANGED`. The remedy is the point.
        "error_invite_response_changed" ->
            "The reply to this invite changed after it was shown, so nothing was saved. Decline this invite and send a new one."
        "error_invite_code_not_shown" ->
            "There is no code to confirm for this invite yet. Try again in a moment."
        else -> UNEXPECTED
    }

    /** The catalogue miss. Kept in step with `:ui-core`'s `error_unexpected`. */
    private const val UNEXPECTED = "Something went wrong. Please try again."
}
