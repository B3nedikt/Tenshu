@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import kotlin.random.Random

// The rules `Mia.kt` serves (`web-app-suite.md` Wave 4, Mia run A). ⚠ The conversation is a
// second implementation of what native `TenshuConversationRepository` keeps (`:mia-shared-logic`
// has no JS target, and by House Rule 5 none should). The rules it copies are listed here and
// pinned in `MiaWebContractTest`; change one side and the other must follow.
//
// 1. **In memory, for the runtime's life** (MQ-D8, §7.6): never persisted. A transcript is the most
//    sensitive thing Mia produces, and durability owes a delete affordance in the same slice — so,
//    as natively, there is none to delete. Signing out or locking clears it (`Session.clear`), the
//    web twin of `MiaLocalRuntime.deprovision` dropping the repository that held it. ⚠ And voids
//    every ask made before the clear, queued or in flight (`MiaConversations.generation`).
// 2. **One conversation per session**, titled by its first question (`recordHistory`).
// 3. **A turn is a question and Mia's answer together**, so a page never splits one from the other.
//    The answer keeps native's verdict verbatim — never collapsed (rule 4).
// 4. **Three verdicts, three sentences** (`MiaAnswer`): `answered` (the plan ran; `hits` may be
//    empty and that means nothing matched), `noMatch` (Mia did not understand, OR understood and
//    will not — a revoked grant, an action this device withholds — and says which in
//    `explanation`), `unavailable` (could not look). ⚠⚠ A refusal is `noMatch`, never an empty
//    `answered` (§14.1). The web adds nothing to the set: `confirm` cannot occur, because the tab's
//    proxy withholds every action (`MiaPlugin`).
// 5. **The grants are native Settings' switches** (run B, `web-app-suite.md` §8, 2026-10-28):
//    `Mia.setGranted` / `setWritable` write the same realm rows `TenshuMiaGrantsRepository` writes,
//    through the same `PreferenceService.set`, and answer with the set read back. A revoked source
//    makes the next question that needs it `noMatch`, naming it (rule 4) — on every device.

/** Catalogue keys this surface mints itself. */
internal object MiaWebErrorKeys {
    /** A blank utterance: nothing to ask. */
    val EMPTY_UTTERANCE = ErrorCopyKey("error_mia_empty_utterance")
    /** Longer than [MAX_UTTERANCE_CHARS]. */
    val UTTERANCE_TOO_LONG = ErrorCopyKey("error_mia_utterance_too_long")
    /** A reader zone the bundled IANA set does not know. */
    val INVALID_ZONE = ErrorCopyKey("error_mia_invalid_zone")
    /** A malformed turns cursor — a caller bug, said loudly. */
    val BAD_CURSOR = ErrorCopyKey("error_mia_bad_cursor")
    /** `pageSize` outside `1..MAX_TURN_PAGE_SIZE`. */
    val BAD_PAGE_SIZE = ErrorCopyKey("error_mia_bad_page_size")
    /** The session was cleared (sign-out, lock) while this question was being asked; nothing was kept. */
    val SESSION_CLEARED = ErrorCopyKey("error_mia_session_cleared")
    /** A conversation id this runtime does not hold (another session's, or cleared at sign-out). */
    val CONVERSATION_NOT_FOUND = ErrorCopyKey("error_mia_conversation_not_found")
    /**
     * The proxy answered with an action to confirm. ⚠ Unreachable while `MiaPlugin` withholds them;
     * if it ever happens it is refused rather than shown, because nothing on web could redeem it.
     */
    val UNEXPECTED_ACTION = ErrorCopyKey("error_mia_unexpected_action")
    /** A grant switch named a source `MiaSource` does not have. Nothing was written. */
    val UNKNOWN_SOURCE = ErrorCopyKey("error_mia_unknown_source")
    /** A write switch on a source with no write tier (everything but `CALENDAR`). Nothing was written. */
    val NO_WRITE_GRANT = ErrorCopyKey("error_mia_no_write_grant")
}

/**
 * The longest utterance [Mia.ask] accepts. Native has no bound because a keyboard is one; a bridge
 * call is not. 500 is several sentences — far beyond anything the grammar parses.
 */
internal const val MAX_UTTERANCE_CHARS = 500

/** Turns per [Mia.turnsPage] at most: a page is one postMessage, never the whole conversation. */
internal const val MAX_TURN_PAGE_SIZE = 100

/**
 * What a turn says when the in-process proxy could not be asked at all — `MiaLocalRuntime`'s
 * sentence, for the same reason: in a tab the proxy is this page, so native's default ("I can't
 * reach Control right now") would name an app that is not involved (§19.3).
 */
internal const val COULD_NOT_LOOK: String =
    "Something went wrong on this device, so I couldn't look that up. Nothing was searched."

// ── the answer, as the bridge carries it ────────────────────────────────────────────────────────

/** A cited row's address: the owning schema's type and its key parts (one for most rows). */
@JsExport
class MiaTargetJs(val type: String, val keys: Array<String>)

@JsExport
class MiaHitJs(
    val target: MiaTargetJs,
    val title: String,
    /** May be `""`. */
    val subtitle: String,
    /** `null` when the row carries no time — unknown, never 1970 (`MiaHit.UNKNOWN_AT_MS`). */
    val atMs: Double?,
)

@JsExport
class MiaAnswerJs(
    /** `answered` | `noMatch` | `unavailable` (rule 4). */
    val verdict: String,
    /** What Mia did, or why it did not — always shown; it is how a wrong plan stays legible. */
    val explanation: String,
    /** Empty unless [verdict] is `answered`, and even then possibly empty: "nothing matched". */
    val hits: Array<MiaHitJs>,
)

@JsExport
class MiaTurnJs(
    val turnId: String,
    val askedAtMs: Double,
    val utterance: String,
    val answer: MiaAnswerJs,
)

@JsExport
class MiaConversationJs(
    val conversationId: String,
    val startedAtMs: Double,
    /** The first question, as asked — native's History row title. */
    val title: String,
    val turnCount: Int,
    val updatedAtMs: Double,
)

@JsExport
class MiaGrantJs(
    /** `MiaSource`'s name: `FILES`, `TIMELINE`, `FITNESS`, `PLACES`, `DEVICES`, `ROUTINES`, … */
    val source: String,
    val read: Boolean,
    /** `null` where nothing can write the source; else whether Mia may (only Calendar today). */
    val write: Boolean?,
)

internal fun MiaAnswer.toJs(): MiaAnswerJs = MiaAnswerJs(
    verdict = verdict.get(),
    explanation = explanation.get(),
    hits = hits.toList().filterIsInstance<MiaHit>().map { it.toJs() }.toTypedArray(),
)

private fun MiaHit.toJs(): MiaHitJs = MiaHitJs(
    target = target.toJs(),
    title = title.get(),
    subtitle = subtitle.get(),
    atMs = at.get().takeIf { it != MiaHit.UNKNOWN_AT_MS }?.toDouble(),
)

internal fun DataId.toJs(): MiaTargetJs = MiaTargetJs(type = type.get(), keys = keyParts().toTypedArray())

private fun DataId.keyParts(): List<String> = when (this) {
    is DataId.StringDataId -> listOf(keyValue.get())
    is DataId.LongDataId -> listOf(keyValue.get().toString())
    // A calendar occurrence: `(eventId, recurrenceId)` (`OccurrenceId`).
    is DataId.CompositeEntityDataId -> keyValues.toList().flatMap { (it as DataId).keyParts() }
    else -> emptyList()
}

// ── rule 1–3: the conversation ──────────────────────────────────────────────────────────────────

/** One stored turn. [seq] orders the conversation and is what the cursor names. */
internal class MiaTurn(val seq: Long, val askedAtMs: Long, val utterance: String, val answer: MiaAnswerJs) {
    fun toJs() = MiaTurnJs(turnId = "t$seq", askedAtMs = askedAtMs.toDouble(), utterance = utterance, answer = answer)
}

internal class MiaConversation(val id: String, val startedAtMs: Long) {
    val turns = mutableListOf<MiaTurn>()

    fun summary() = MiaConversationJs(
        conversationId = id,
        startedAtMs = startedAtMs.toDouble(),
        title = turns.first().utterance,
        turnCount = turns.size,
        updatedAtMs = turns.last().askedAtMs.toDouble(),
    )
}

/** History (newest first; at most one entry, rule 2) and whether an answer is being composed. */
internal class MiaHistoryState(val conversations: List<MiaConversationJs>, val replying: Boolean)

/**
 * The runtime's one conversation store. ⚠ Single-threaded by construction (JS), and [Mia.ask]
 * serialises asks, so a question and its answer are always appended together.
 */
internal object MiaConversations {
    private var current: MiaConversation? = null
    private var nextSeq = 1L
    private val state = MutableStateFlow(MiaHistoryState(emptyList(), replying = false))

    val history: StateFlow<MiaHistoryState> get() = state.asStateFlow()

    /**
     * Moves on every [clear]. ⚠⚠ An ask captures it when it is CALLED and appends only if it has not
     * moved: `DeviceMenu.lockNow` clears without reloading the runtime, so an ask still in flight (or
     * queued behind another) would otherwise write its pre-lock question into the next session.
     */
    var generation = 0L
        private set

    fun get(id: String): MiaConversation? = current?.takeIf { it.id == id }

    fun setReplying(replying: Boolean) = state.update { MiaHistoryState(it.conversations, replying) }

    /** Appends a turn, starting the session's conversation on its first question (rule 2). */
    fun append(askedAtMs: Long, utterance: String, answer: MiaAnswerJs): Pair<MiaConversation, MiaTurn> {
        val conversation = current ?: MiaConversation(id = newConversationId(askedAtMs), startedAtMs = askedAtMs)
            .also { current = it }
        val turn = MiaTurn(nextSeq++, askedAtMs, utterance, answer)
        conversation.turns += turn
        publish()
        return conversation to turn
    }

    /** Sign-out (rule 1): the conversation goes, and its id stops resolving. */
    fun clear() {
        generation++
        current = null
        publish()
    }

    private fun publish() = state.update { MiaHistoryState(listOfNotNull(current?.summary()), it.replying) }

    /** ⚠ Fresh per conversation, so a cursor from a cleared one can never page into the next. */
    private fun newConversationId(atMs: Long) = "c$atMs-" + Random.nextLong().toULong().toString(16)
}

// ── the turns cursor ────────────────────────────────────────────────────────────────────────────

/**
 * `m1.<seq>`: every turn strictly older than `seq`. Opaque to TypeScript. Newest first, so the head
 * is the latest exchange and each next page reaches further back. ⚠ Keyset, not offset: a turn
 * appended between two calls is above the cursor and never shifts a later page.
 */
internal object MiaTurnCursor {
    private const val PREFIX = "m1."

    /** `Long.MAX_VALUE` for the head (`null` / `""`), `null` for a malformed token. */
    fun decode(cursor: String?): Long? {
        if (cursor.isNullOrEmpty()) return Long.MAX_VALUE
        if (!cursor.startsWith(PREFIX)) return null
        val digits = cursor.removePrefix(PREFIX)
        // Canonical only: no sign, no leading zero, so one position has one spelling.
        if (digits.isEmpty() || digits.any { it !in '0'..'9' } || (digits.length > 1 && digits[0] == '0')) return null
        return digits.toLongOrNull()?.takeIf { it > 0 }
    }

    fun encode(beforeSeq: Long): String = PREFIX + beforeSeq
}
