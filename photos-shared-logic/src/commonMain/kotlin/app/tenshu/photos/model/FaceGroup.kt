package app.tenshu.photos.model

/**
 * Photographs this device currently believes show the same person (IN-6 F3).
 *
 * ⚠⚠ **A group, not a person** — see `GroupedFace`'s note for why the distinction is load-bearing.
 * [groupId] is valid for the call that produced it and must never be persisted: grouping is redone
 * from device-local templates, so an index pass can renumber everything. Route on it while this
 * list is held (`Destination.Smart` does), and no longer.
 *
 * ⚠ One photograph can appear in **two** groups — two people in one frame — so these lists overlap
 * and their sizes do not sum to the library's.
 */
data class FaceGroup(
    val groupId: String,
    val filePaths: List<String>,
    /**
     * Each member FACE, with where it sits and how crowded its photograph is (IN-6 F6a).
     *
     * ⚠ Defaulted empty so a group built without face detail — every test fixture before F6a, and a
     * proxy on an older build — still groups; the header then falls back to the whole photograph.
     */
    val faces: List<GroupFace> = emptyList(),
)

/** One face of a [FaceGroup] (IN-6 F6a). */
data class GroupFace(
    val path: String,
    val faceIndex: Int,
    val region: FaceRegion,
    /**
     * Every face the DETECTOR found in the photograph, across all groups — the proxy's count, not
     * this list's. ⚠⚠ Detected, not present: see `GroupedFace.facesInPhoto` for why nothing may yet
     * read this as "the people in the photo".
     */
    val facesInPhoto: Int,
)

/**
 * Where a face is, as fractions (0..1) of the upright photograph's width and height.
 *
 * ⚠ Position, never identity — the same guarantee `GroupedFace` gives A40.
 */
data class FaceRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** The face a person header shows, and the photograph it is cut from (IN-6 F6a, §7's cover rule). */
data class FaceCover(val path: String, val region: FaceRegion)

/**
 * §7's cover rule, as far as today's data reaches: **the largest face**. ⚠ The "most frontal" half
 * needs a head pose, which the template store does not keep; add it when it does, not by guessing.
 */
fun List<GroupFace>.largestFace(): FaceCover? =
    maxByOrNull { it.region.area }?.let { FaceCover(it.path, it.region) }

/**
 * What the grouping had to say about itself.
 *
 * ⚠⚠ Mirrors [SemanticSearchStatus] deliberately, for the same reason: *"nothing matched"* and
 * *"this cannot run here"* are different answers. People is the sharper case — a library with no
 * recognisable faces produces a legitimately empty grid, so a refusal rendered as a result is
 * invisible to the user **and** to us.
 */
sealed interface PeopleStatus {
    /** Ran, and its groups (if any) are in the list. */
    data object Available : PeopleStatus

    /**
     * Did not run. [reason] is a `FaceGroupsUnavailable.Reasons` constant, kept raw so the UI layer
     * owns the sentence — the proxy's vocabulary and the screen's voice are different things.
     *
     * ⚠ `UNCALIBRATED` is the one with no user action behind it: it means this build shipped
     * without a threshold, and a screen that offered a button would be inventing a remedy.
     */
    data class Unavailable(val reason: String) : PeopleStatus

    /** Something else went wrong. ⚠ Not merged into [Unavailable]: that one names a cause. */
    data object Failed : PeopleStatus
}

/**
 * What happened when a name was written (IN-6 F3b).
 *
 * ⚠⚠ **A boolean was not enough, and the dialog proved it.** Collapsing every failure to
 * `false` meant one sentence had to explain all of them, and the one it chose — "People is
 * switched off" — is wrong for a Control that is merely not running, for an older Control that
 * does not have the operation (adding an op does not raise the service version, so this is a
 * NORMAL mixed-version state), and for an internal save failure. Telling a user to check a setting
 * that is already correct is worse than saying "that did not work".
 */
sealed interface NamingOutcome {
    data object Saved : NamingOutcome

    /** The capability is off for this realm or this device. The one with an action behind it. */
    data object NoConsent : NamingOutcome

    /**
     * Some photographs were named and then it stopped.
     *
     * ⚠⚠ **Its own case, because reporting it as a failure is a lie the user can act on.**
     * A person with many photographs is named in several calls; if a later one is refused, the
     * earlier rows are already durable and SYNCED. "That name wasn't saved" then sends the user
     * to retype a name that is half applied, and the half they cannot see is the half that is
     * wrong.
     */
    data object PartiallySaved : NamingOutcome

    /**
     * Anything else — Control not running, an older Control without the operation, a save that
     * failed inside the proxy.
     *
     * ⚠ Deliberately one case rather than three: the user's next move is the same for all of
     * them (try again), and three sentences that all mean "try again" is a vocabulary nobody reads.
     * The *reason* still reaches the log through the typed error; this is only what a screen says.
     */
    data object Failed : NamingOutcome
}
