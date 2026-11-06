package app.tenshu.notify

import kotlin.time.Instant

/**
 * How loudly a channel's alerts arrive.
 *
 * ⚠ Three levels, not the platform's five. Android has `MIN`…`MAX` and iOS has an
 * entirely different vocabulary; mapping a rich enum onto both would mean
 * inventing distinctions neither honours. These are the three that behave
 * differently *everywhere*: does it interrupt, does it appear, does it only sit
 * in the shade.
 */
enum class AlertImportance {
    /** Heads-up and sound — for something time-sensitive the user asked for. */
    HIGH,
    /** Appears with sound, no interruption. */
    DEFAULT,
    /** Silent; ongoing work and status. */
    LOW,
}

/**
 * A named group of alerts, as the user sees it in their OS settings.
 *
 * ⚠⚠ **Channels are a promise to the user, not an implementation detail.** On
 * Android the user can silence a channel forever, and its importance is fixed at
 * *creation* — raising it later has no effect on anyone who already has the app.
 * So a channel must be declared once, with the importance it will always have,
 * and a new behaviour needs a **new channel** rather than a louder old one.
 *
 * ⚠ [id] is stable and user-visible-by-proxy; changing it silently resets
 * whatever the user configured.
 */
data class AlertChannel(
    val id: String,
    val name: String,
    val importance: AlertImportance,
)

/**
 * One thing to show the user on this device's OS.
 *
 * ⚠⚠ **[id] is stable identity, not a serial number.** Every platform replaces
 * an alert with a matching id rather than stacking a second copy, which is what
 * makes re-posting safe and what lets a caller re-plan freely. A random id per
 * post would turn one reminder into a pile.
 *
 * ⚠ [title] and [body] are **final**. Nothing downstream may enrich them — see
 * `ReminderPlanner`, where redaction happens at compose time precisely because
 * on Apple platforms the OS is handed this text days in advance and the app
 * never sees it again.
 */
data class AppAlert(
    val id: String,
    val channel: AlertChannel,
    val title: String,
    val body: String = "",
    /**
     * Opaque payload handed back when the user taps.
     *
     * ⚠ A string the *app* interprets, deliberately: this module must not know
     * about any app's navigation, and a typed route would either couple it to
     * one app or grow into a routing framework. Blank means "just open the app".
     */
    val tapPayload: String = "",
    /**
     * Buttons on the alert itself (CA-5f).
     *
     * ⚠⚠ **An action is handled without opening the app**, which is the whole
     * reason it exists — "snooze" that first makes you unlock your phone is not
     * snooze. Whatever handles it therefore runs in whichever process posted,
     * with a few seconds and no UI; see `AlertActionHandler`.
     *
     * ⚠ Empty on most alerts, and that is the right default: an action the user
     * did not ask for is a second thing to read on a lock screen.
     */
    val actions: List<AlertAction> = emptyList(),
    /**
     * The app a tap should open, when it is not the one posting.
     *
     * ⚠⚠ **Not cosmetic.** On Android the process that owns the alarm is the one
     * that posts — Control — so without this every Calendar reminder taps through
     * to *Control*, which has no idea what an occurrence is. A platform with no
     * notion of separate app processes (macOS, iOS, web) ignores it.
     *
     * ⚠ Blank means "the posting app", so nothing that predates this changes.
     */
    val targetApp: String = "",
)

/**
 * One button on an [AppAlert].
 *
 * ⚠ [id] is what the handler switches on, and it must be stable: a `PendingIntent`
 * for an already-posted alert outlives the process that made it, so renaming an
 * action id strands every notification currently on screen.
 *
 * ⚠ No icon. Android has shown action icons nowhere since API 24, iOS never had
 * them, and carrying a per-platform drawable through this seam would buy nothing.
 */
data class AlertAction(
    val id: String,
    val label: String,
)

/** An [AppAlert] the OS should hold and deliver at [deliverAt]. */
data class ScheduledAlert(
    val alert: AppAlert,
    val deliverAt: Instant,
) {
    val id: String get() = alert.id
}

/**
 * Keys the seam and its consumers must agree on.
 *
 * ⚠⚠ In `commonMain` although only Android uses them, because they are **contract,
 * not implementation**: the process that writes the extra and the process that
 * reads it are deliberately different apps (Control posts, Calendar receives), so
 * a private constant on the Android presenter would be a string duplicated across
 * an app boundary with nothing to catch a typo — a tap that silently does nothing.
 */
object AlertIntents {

    /** The extra an app reads to route a notification tap. */
    const val EXTRA_TAP_PAYLOAD = "app.tenshu.notify.tapPayload"
}
