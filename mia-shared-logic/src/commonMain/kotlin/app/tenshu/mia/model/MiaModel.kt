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
    ) : MessageBody
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
