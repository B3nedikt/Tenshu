package app.tenshu.photos.ui.screens.collections

import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome
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
 * ⚠⚠ [modelsSwitch] says WHERE Settings → Models is (`ios-face-grouping.md` F7): Control's on Android,
 * Photos' own on iOS, where Photos runs its own proxy and Control-on-iOS has no such screen. The
 * search bar's rule (`PhotoSearchCopy.off`). ⚠ On iOS a pass runs when *Index now* is tapped, so the
 * not-indexed line names that button rather than Android's idle-and-charging schedule.
 * [faceModelPending] (this app only): the face model is installed and still being prepared or probed.
 *
 * ⚠ A refusal is shown even when [hasPeople]: a name synced from another device appears here from
 * its labels alone, while this device still groups nobody. Only `Available` goes quiet with people.
 */
fun peopleStatusLine(
    status: PeopleStatus,
    hasPeople: Boolean,
    groupingRunsHere: Boolean,
    modelsSwitch: ModelsSwitchHome = ModelsSwitchHome.CONTROL,
    faceModelPending: Boolean = false,
): String? {
    if (!groupingRunsHere) return null
    if (modelsSwitch == ModelsSwitchHome.THIS_APP) return thisAppLine(status, hasPeople, faceModelPending)
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

/**
 * [peopleStatusLine] where this app runs its own proxy and has its own Models screen (Photos on iOS).
 * ⚠ The same reasons and the same voice as Control's lines; only where to go, and what starts a pass, differ.
 */
private fun thisAppLine(status: PeopleStatus, hasPeople: Boolean, faceModelPending: Boolean): String? = when (status) {
    PeopleStatus.Available -> if (hasPeople) null else "No people found yet."
    PeopleStatus.Failed -> "People isn't available right now."
    is PeopleStatus.Unavailable -> when (status.reason) {
        FaceGroupsUnavailable.Reasons.NO_CONSENT ->
            "To see people here, turn on \"Group photos by people\" for this device in Photos, " +
                "under Settings → Models."
        // ⚠ One model here: Vision finds the faces, so only the embedder is downloaded. ⚠⚠ While it is
        // being prepared or probed the proxy answers `NO_BACKEND` too, and "download" would ask for a
        // model the phone already has ([faceModelPending], the face readiness's answer).
        FaceGroupsUnavailable.Reasons.NO_BACKEND ->
            if (faceModelPending) {
                "People appear once the face model is ready on this device."
            } else {
                "To see people here, download the face model in Photos, under Settings → Models."
            }
        FaceGroupsUnavailable.Reasons.NOT_INDEXED ->
            "People appear after Photos looks through your photos on this device. " +
                "Tap Index now, under Settings → Models."
        else -> "People isn't available on this device yet."
    }
}
