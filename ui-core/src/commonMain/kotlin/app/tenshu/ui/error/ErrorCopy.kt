package app.tenshu.ui.error

import androidx.compose.runtime.Composable
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.error_content_pinned
import app.tenshu.ui.resources.error_still_syncing
import app.tenshu.ui.resources.error_move_needs_local_bytes
import app.tenshu.ui.resources.error_content_unavailable
import app.tenshu.ui.resources.error_control_not_running
import app.tenshu.ui.resources.error_dispatch_missing_parameter
import app.tenshu.ui.resources.error_dispatch_unknown_parameter
import app.tenshu.ui.resources.error_dispatch_wrong_parameter
import app.tenshu.ui.resources.error_operation_invalid_parameter
import app.tenshu.ui.resources.error_index_pass_refused
import app.tenshu.ui.resources.error_internal
import app.tenshu.ui.resources.error_invite_code_not_shown
import app.tenshu.ui.resources.error_invite_response_changed
import app.tenshu.ui.resources.error_generic
import app.tenshu.ui.resources.error_not_found
import app.tenshu.ui.resources.error_invalid_caller
import app.tenshu.ui.resources.error_open_target_unavailable
import app.tenshu.ui.resources.error_unannounced_session
import app.tenshu.ui.resources.error_unsupported_operation
import app.tenshu.ui.resources.error_entitlement_unverified
import app.tenshu.ui.resources.error_recovery_phrase_unreadable
import app.tenshu.ui.resources.error_device_already_set_up
import app.tenshu.ui.resources.error_relay_route_unsupported
import app.tenshu.ui.resources.error_model_unavailable
import app.tenshu.ui.resources.error_face_groups_unavailable
import app.tenshu.ui.resources.error_semantic_search_unavailable
import app.tenshu.ui.resources.error_already_exists
import app.tenshu.ui.resources.error_insufficient
import app.tenshu.ui.resources.error_no_storage
import app.tenshu.ui.resources.error_metered_network_refused
import app.tenshu.ui.resources.error_unexpected
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.tenshu.error.ErrorCopyKey

/*
 * The Compose half of the error copy catalogue — `docs/proposals/core-error-copy-catalogue.md`.
 *
 * ⚠⚠ §5.0: **no proxy code ever creates a localised string.** A typed error crosses the proxy
 * boundary; the app side turns it into copy, here. On Android the two sides are different
 * *packages* — the `*-service-proxy` modules run in Control's process while a feature app renders
 * — so a sentence authored below this line is one app writing another app's copy.
 *
 * This file never sees a `TError` (§5.1). It takes an [ErrorCopyKey], which `:core` derives from
 * the error's schema id, so the same key names the same entry in all three renderers: here, in
 * `tenshu-sdk-ios-core` for SwiftUI, and in the web SDK's i18next catalogue.
 */

/**
 * The user-facing label for [this] error key.
 *
 * ⚠⚠ A catalogue miss renders the generic sentence, **never** the error's own `message`. Showing
 * the developer's English is the thing this catalogue exists to stop, and it is exactly what a
 * proxy author's diagnostic string would look like on a user's screen. A missing entry is a bug to
 * fix in `strings.xml`, not something to paper over at the call site — the id itself belongs in a
 * detail row or the `ErrorEvent`, where a developer will look for it.
 */
@Composable
fun ErrorCopyKey.label(): String =
    stringResource(CATALOGUE[value] ?: Res.string.error_unexpected)

/**
 * As [label], but a miss falls back to the key's own mechanical humanisation
 * (`error_not_found` -> "Not found") instead of the generic sentence.
 *
 * ⚠⚠ **Developer surfaces only** — Settings → Errors is the only caller. The two surfaces
 * genuinely want different fallbacks: on a diagnostics list a readable id beats "Something went
 * wrong", because the reader is looking for *which* error; on a user-facing screen that same
 * string leaks the id vocabulary. Keeping both is deliberate (§5.1) — collapsing them onto the
 * generic sentence would have silently cost the Errors screen its labels for every error with no
 * entry yet.
 */
@Composable
fun ErrorCopyKey.developerLabel(): String =
    CATALOGUE[value]?.let { stringResource(it) } ?: humanised()

/**
 * Derived key → catalogue entry, for every error type this module has copy for.
 *
 * ⚠ The key strings here duplicate the `name=` attributes in `strings.xml`, because Compose
 * Resources generates typed accessors and offers no lookup by name (`Res.allStringResources` does
 * not exist on the generated class at CMP 1.12.0 — tried first, for exactly this reason). So
 * adding an error type is a two-place edit, and nothing about that is compile-checked.
 * `ErrorCopyCatalogueTest` is what makes a mismatch a test failure rather than a silent fallback,
 * and it is why §5.1 requires that test alongside a derived key.
 *
 * `internal` so the test can walk it in both directions; not part of the module's API.
 */
internal val CATALOGUE: Map<String, StringResource> = mapOf(
    "error_content_pinned" to Res.string.error_content_pinned,
    "error_content_unavailable" to Res.string.error_content_unavailable,
    "error_control_not_running" to Res.string.error_control_not_running,
    "error_dispatch_missing_parameter" to Res.string.error_dispatch_missing_parameter,
    "error_dispatch_unknown_parameter" to Res.string.error_dispatch_unknown_parameter,
    "error_dispatch_wrong_parameter" to Res.string.error_dispatch_wrong_parameter,
    "error_operation_invalid_parameter" to Res.string.error_operation_invalid_parameter,
    "error_still_syncing" to Res.string.error_still_syncing,
    "error_move_needs_local_bytes" to Res.string.error_move_needs_local_bytes,
    "error_internal" to Res.string.error_internal,
    "error_invite_code_not_shown" to Res.string.error_invite_code_not_shown,
    "error_invite_response_changed" to Res.string.error_invite_response_changed,
    "error_generic" to Res.string.error_generic,
    "error_not_found" to Res.string.error_not_found,
    "error_invalid_caller" to Res.string.error_invalid_caller,
    "error_open_target_unavailable" to Res.string.error_open_target_unavailable,
    "error_unannounced_session" to Res.string.error_unannounced_session,
    "error_unsupported_operation" to Res.string.error_unsupported_operation,
    "error_entitlement_unverified" to Res.string.error_entitlement_unverified,
    "error_relay_route_unsupported" to Res.string.error_relay_route_unsupported,
    "error_recovery_phrase_unreadable" to Res.string.error_recovery_phrase_unreadable,
    "error_device_already_set_up" to Res.string.error_device_already_set_up,
    "error_semantic_search_unavailable" to Res.string.error_semantic_search_unavailable,
    "error_face_groups_unavailable" to Res.string.error_face_groups_unavailable,
    "error_model_unavailable" to Res.string.error_model_unavailable,
    "error_index_pass_refused" to Res.string.error_index_pass_refused,
    "error_already_exists" to Res.string.error_already_exists,
    "error_insufficient" to Res.string.error_insufficient,
    "error_no_storage" to Res.string.error_no_storage,
    "error_metered_network_refused" to Res.string.error_metered_network_refused,
)
