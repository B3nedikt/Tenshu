package app.tenshu.mia.model

import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import kotlin.time.Instant

/**
 * Mia conversation model, M3 (docs/proposals/home-and-mia-apps.md §4/§7). Plain classes —
 * the real assistant rides the Voice App Service later; the message shapes stay.
 *
 * Rich results are first-class from day one so the realm-data-assistant story (photos from
 * Drive, places from Timeline) is visible in the UI rather than bolted on later. In M3 the
 * payloads are mock placeholders.
 */
data class MiaMessage(
    val id: String,
    val at: Instant,
    val fromUser: Boolean,
    val body: MessageBody,
)

sealed interface MessageBody {

    data class Text(val text: String) : MessageBody

    /** A grid of photos found in the user's realm (Drive). Mock: labelled placeholders. */
    data class PhotoGrid(val caption: String, val photoLabels: List<String>) : MessageBody

    /** A place found in the user's Timeline. */
    data class PlaceCard(val name: String, val detail: String) : MessageBody

    /** Confirmation that a home-device command was carried out. */
    data class DeviceAck(val text: String) : MessageBody

    /**
     * The rows Mia found, as citations (`mia-query-layer.md` MQ-D1) — files, places,
     * moments, workouts or devices, all in one cell since MQ-2.
     *
     * [caption] is the planner's explanation — what was actually searched — so the user
     * can check the question Mia answered against the one they asked.
     */
    data class Citations(val caption: String, val hits: List<Hit>) : MessageBody

    /**
     * Something Mia will do **only if you tap** (`mia-query-layer.md` MQ-D7).
     *
     * ⚠⚠ [title] is the **resolved** action — the device Mia actually found, not the words
     * that were typed. Confirming a partial utterance and letting the resolution land unseen
     * is the failure this cell exists to prevent.
     *
     * ⚠ [done] flips after the tap so the button cannot be pressed twice. The proxy also
     * refuses a spent token, so this is politeness rather than the control.
     */
    data class Confirmation(
        val title: String,
        val detail: String,
        val token: String,
        val done: Boolean = false,
        /**
         * Another reading the user can pick in one tap instead (§25, CL-D6): *"20:00 instead?"* for
         * a bare hour. ⚠ A tap never writes: it replaces this card with a new one.
         */
        val alternatives: List<Choice> = emptyList(),
        /** Replaced by an alternative's card, so it can no longer be confirmed. */
        val replaced: Boolean = false,
        /** Tapped, and nothing was written (expired, spent or failed): spent, but never "Done". */
        val refused: Boolean = false,
        /** Tapped, and no answer came back: it may or may not have run. Spent, never "Not done". */
        val outcomeUnknown: Boolean = false,
    ) : MessageBody {
        /** Tapped already, whatever came of it: the card can neither be confirmed nor changed. */
        val spent: Boolean get() = done || replaced || refused || outcomeUnknown
    }

    /**
     * Mia asking back for one value (`mia-query-layer.md` §25, CL-2).
     *
     * ⭐ Each [Choice] is a canned reply: a tap shows its [Choice.label] as the user's message and
     * sends its [Choice.reply]. A typed or spoken answer goes the same way while this is [open].
     *
     * ⚠ [open] flips to false once the question is answered or superseded, so older chips render
     * disabled. One question is open at a time, and the proxy refuses a spent token anyway, so this
     * is honesty rather than the control (CL-Q3).
     */
    data class Clarification(
        val question: String,
        val choices: List<Choice>,
        val token: String,
        val open: Boolean = true,
    ) : MessageBody

    /** A chip: what it shows, and what a tap sends back. */
    data class Choice(val label: String, val reply: String)
}

/**
 * One cited row, whatever it came from. [kind] names the source so the cell can pick a
 * glyph; [reference] is the owning app's own identifier, kept opaque here because
 * resolving it is that app's job.
 *
 * [atMs] is `0` when the row carries no time at all: unknown, not 1970.
 */
data class Hit(
    /**
     * The row's identity — what tapping this citation opens
     * (`control-cross-app-open.md` TT-1c).
     *
     * ⚠⚠ It replaced a `kind` string and a `reference` string. `reference` was carried up here
     * from MQ-1 onward and **read by nothing** — the citation was inert, so the address had no
     * consumer. This is that field finally doing its job, in the platform's own vocabulary.
     *
     * ⚠ The glyph switches on [typeId], which is this id's type.
     */
    val target: DataId,
    val title: String,
    val subtitle: String,
    val atMs: Long,
) {
    /** The owning schema's type id — `tenshu/file`, `tenshu/place/place`, … */
    val typeId: String get() = target.type.get()
}

/** A past conversation, as the History tab lists it. */
data class Conversation(
    val id: String,
    val startedAt: Instant,
    /** The user's opening request — the row title. */
    val title: String,
    val messages: List<MiaMessage>,
)
