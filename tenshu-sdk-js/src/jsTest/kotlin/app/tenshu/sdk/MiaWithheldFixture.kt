package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import org.tenshu.home.device.HomeDevice
import org.tenshu.routine.Routine

/**
 * The tab's three write-shaped utterances, over a realm in which each would RESOLVE under
 * `MiaActionOffer.OFFERED` — one device and one routine whose names the utterances name — and the
 * `MiaActionOffer.WITHHELD` sentence each must get instead (`QueryPlanner`'s `WITHHELD_*`, verbatim).
 *
 * ⚠⚠ Without the rows a refusal proves nothing: "turn off the … light" with no device answers
 * `noMatch` ("I couldn't find a device…"), and so does a routine nobody has, so a `noMatch`-only
 * assertion stays green with `WITHHELD` dropped. The native twin over the same rows,
 * where all three become confirmations, is `AskMiaActionOfferTest`.
 *
 * ⚠ The jsTest suite shares ONE runtime, so the names are random and each call seeds its own pair.
 */
internal object MiaWithheldFixture {

    const val WITHHELD_DEVICE =
        "I can't switch anything on or off from this device yet — here I can only look things " +
            "up. I can tell you what state something is in: “is the kitchen light on”."

    const val WITHHELD_ROUTINE =
        "I can't run routines from this device yet — here I can only look things up."

    const val WITHHELD_EVENT =
        "I can't add anything to your calendar from this device yet — here I can only look " +
            "things up. I can tell you what is already there: “what's on my calendar tomorrow”."

    /** Utterance → the sentence it must be refused with. */
    class Seeded(val eventTitle: String, val utterances: List<Pair<String, String>>)

    /** Letters only: the grammar tokenises on words, and a digit run is not one. */
    private fun word(n: Int = 8) = (1..n).map { ('a'..'z').random() }.joinToString("")

    fun seed(): Seeded {
        val ec = JsRuntime.executionContext
        val device = word()
        val routine = word()
        ec.saveData(
            HomeDevice.create(
                context = ec.context,
                deviceId = "mia-${word()}",
                bridgeId = "bridge",
                kind = "light",
                name = "$device light",
                room = "Kitchen",
                joinedAtEpochMs = 1L,
            ),
        )
        ec.saveData(
            Routine.create(
                context = ec.context,
                routineId = "mia-${word()}",
                name = "$routine morning",
                enabled = true,
                state = Routine.STATE_ACTIVE,
                timeZoneId = "UTC",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                programJson = "{}",
                programVersion = 1L,
            ),
        )
        val title = "lunch with ${word()}"
        return Seeded(
            eventTitle = title,
            utterances = listOf(
                "add $title tomorrow at 1" to WITHHELD_EVENT,
                "turn off the $device light" to WITHHELD_DEVICE,
                "run my $routine morning routine" to WITHHELD_ROUTINE,
            ),
        )
    }
}
