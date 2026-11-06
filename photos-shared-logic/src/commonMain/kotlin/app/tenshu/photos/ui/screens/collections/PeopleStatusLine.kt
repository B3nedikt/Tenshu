package app.tenshu.photos.ui.screens.collections

import app.tenshu.photos.model.PeopleStatus
import org.tenshu.inference.FaceGroupsUnavailable

/**
 * The one line under the People header that says why it holds nobody, or `null` for no line.
 *
 * ⚠⚠ **Why the section says anything at all.** An absent People section cannot be told apart from
 * a library with nobody in it, from People switched off, or from a phone that has not indexed yet,
 * and only the last of those fixes itself. [PeopleStatus] was carried to the state precisely so the
 * screen could tell them apart; until this line nothing rendered it.
 *
 * ⚠ Photos' own wording, as `PhotoSearchScreen`'s `unavailableText` is: the reasons are shared
 * (`FaceGroupsUnavailable.Reasons`), the sentences are not.
 *
 * ⚠⚠ `NO_CONSENT` names the feature in Control's own words, and says **for this device**. The
 * proxy returns it when either the realm's answer or this device's is off and cannot say which;
 * Control's feature screen has one switch that settles both — *This device*
 * (`inference-service.md` §38.2).
 *
 * ⚠ `NO_BACKEND` names the models, not a model name: the catalog owns the names and Photos never sees
 * them, and the backend needs both face artifacts (detector and embedder) before it claims the
 * capability.
 *
 * ⚠⚠ [groupingRunsHere] silences every line on a platform that cannot group faces. Its proxy answers
 * `NO_BACKEND` before consent, forever, and a remedy it cannot take is a nag, not information.
 *
 * ⚠ A refusal is shown even when [hasPeople]: a name synced from another device appears here from
 * its labels alone, while this device still groups nobody. Only `Available` goes quiet with people.
 */
fun peopleStatusLine(
    status: PeopleStatus,
    hasPeople: Boolean,
    groupingRunsHere: Boolean,
): String? {
    if (!groupingRunsHere) return null
    return when (status) {
        PeopleStatus.Available -> if (hasPeople) null else "No people found yet."
        PeopleStatus.Failed -> "People isn't available right now."
        is PeopleStatus.Unavailable -> when (status.reason) {
            FaceGroupsUnavailable.Reasons.NO_CONSENT ->
                "To see people here, turn on \"Group photos by people\" for this device in Control, " +
                    "under Settings → Models."
            FaceGroupsUnavailable.Reasons.NO_BACKEND ->
                "To see people here, download the face models in Control, under Settings → Models."
            FaceGroupsUnavailable.Reasons.NOT_INDEXED ->
                // ⚠ "Idle and charging" is Control's own schedule (Settings → Models), said here so
                // the wait does not read as a stall.
                "People appear after this device indexes your photos, while it's idle and charging."
            // ⚠ UNCALIBRATED is a gap in the build with no user remedy, so no action is offered; an
            // unknown reason (a newer proxy) gets the same honest, generic answer.
            else -> "People isn't available on this device yet."
        }
    }
}
