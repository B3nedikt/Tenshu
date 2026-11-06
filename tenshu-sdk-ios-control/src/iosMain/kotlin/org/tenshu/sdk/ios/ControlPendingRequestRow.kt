package org.tenshu.sdk.ios

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestKind
import app.tenshu.control.notifications.pendingRequestFacts
import org.tenshu.core.common.util.TimestampUtil
import kotlin.time.Instant

/**
 * Which inbound request a row is, as a closed set the SwiftUI center switches on.
 *
 * A Kotlin `enum class` for the same reason [ControlEventKind] is one: SKIE bridges it as a
 * real Swift enum, so the `switch` is **exhaustive** and the 6e app-reconnect variant
 * (`control-central-notifications.md` Phase 5) fails the Swift build when it lands rather
 * than falling into a `default:` that renders a card with no actions.
 */
enum class ControlPendingRequestKind {
    MEMBERSHIP_GRANT,
    CONTACT_INVITE_REDEEMED,
}

/**
 * One inbound actionable request, flattened for SwiftUI — the [ControlEventRow] treatment
 * applied to the *actionable* tier.
 *
 * Why flatten: `PendingRequest` is a sealed interface whose two leaves carry different
 * fields, and acting on one needs the **leaf**, not the interface. Handing Swift the sealed
 * type would mean `onEnum(of:)` in the view plus a cast back to the leaf for every action —
 * the pattern `ControlSurface` already refuses ("never switching sealed cases in Swift").
 * So the row carries the facts, and [ControlSurface] takes the **id** back for actions and
 * re-resolves the leaf Kotlin-side, where the `when` is exhaustive and total.
 *
 * Copy lives in Swift (ADR 0016): this carries who and what, never a sentence.
 *
 * ⚠ [timestampLabel] is absolute and locale-formatted by the same shared [TimestampUtil]
 * the Compose surface uses — precomputed safely because it does not go stale, exactly as
 * [ControlEventRow] records. A relative "3 minutes ago" would have to be a live value.
 */
class ControlPendingRequestRow internal constructor(
    /** `PendingRequest.id` — content-stable, and what every action is keyed by. */
    val id: String,
    val kind: ControlPendingRequestKind,
    /**
     * Who the request is from: the grantor's fingerprint for a grant, the peer's
     * **claimed** (quarantined, self-asserted) name for a redeemed invite.
     */
    val subject: String,
    /**
     * The §5.3 SAS the user compares out-of-band before confirming an invite. Empty for a
     * membership grant, which has no SAS step.
     */
    val verificationCode: String,
    /**
     * The raw claimed fingerprint this row was rendered from (`""` for a grant). ⚠⚠ Swift hands
     * it back to `ControlSurface.acceptRequest` from the row the user tapped — never a re-read —
     * so a claim that flips between render and tap cannot be confirmed with the old code's tick
     * (threat model T-CI-10).
     */
    val shownFingerprint: String,
    /** True once dismissed (marked seen) on any device — the card then hides its dismiss affordance. */
    val seen: Boolean,
    /** An action is in flight; the card disables its buttons. */
    val inProgress: Boolean,
    /** The last action's failure message, or null. */
    val errorMessage: String?,
    /** Absolute, localized, formatted by the same shared [TimestampUtil] the Compose surface uses. */
    val timestampLabel: String,
) {
    companion object {
        /**
         * ⚠ The projection rules are **not** here: they are `pendingRequestFacts` in
         * `commonMain`, pinned by `PendingRequestPresentationTest`. This file cannot be
         * compiled off a Mac (Kotlin/Native Apple tasks are skipped, not run, on a Linux
         * host), so anything decided here would ship unverified. What is left is the
         * mechanical part — enum translation and one date format.
         */
        @OptIn(kotlin.time.ExperimentalTime::class)
        internal fun from(
            request: PendingRequest,
            seen: Boolean,
            inProgress: Boolean,
            errorMessage: String?,
        ): ControlPendingRequestRow {
            val facts = pendingRequestFacts(request)
            return ControlPendingRequestRow(
                id = facts.id,
                kind = when (facts.kind) {
                    PendingRequestKind.MEMBERSHIP_GRANT -> ControlPendingRequestKind.MEMBERSHIP_GRANT
                    PendingRequestKind.CONTACT_INVITE_REDEEMED ->
                        ControlPendingRequestKind.CONTACT_INVITE_REDEEMED
                },
                subject = facts.subject,
                verificationCode = facts.verificationCode,
                shownFingerprint = facts.shownFingerprint,
                seen = seen,
                inProgress = inProgress,
                errorMessage = errorMessage,
                timestampLabel = TimestampUtil.formatTimestamp(
                    Instant.fromEpochMilliseconds(facts.timestampMillis)
                ),
            )
        }
    }
}

/**
 * One render of the notification surface: every request as a row, plus the unread count the
 * bell badge shows.
 *
 * Combined **Kotlin-side** rather than left as the view model's four separate `StateFlow`s
 * (`requests` / `seenIds` / `unread` / `actionState`). Four `for await` loops in SwiftUI
 * would each re-render from a partial picture — a card could show its spinner a frame before
 * the request it belongs to exists — and recombining them in Swift is exactly the work
 * `combine` already does correctly here.
 */
class ControlNotificationsSnapshot internal constructor(
    val rows: List<ControlPendingRequestRow>,
    /** Not `rows.size`: the center lists dismissed requests, the badge does not count them. */
    val unreadCount: Int,
)
