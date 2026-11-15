package app.tenshu.photos.model

/**
 * One "this face is not [name]" the user said on this device (IN-6 F6c name suggestions) — the
 * proxy's device-local `RejectedFace`, as Photos needs it.
 *
 * ⚠ Keyed by the face's BOX in its photograph, never by a group id or a face index: groups are
 * re-clustered per read and their ids name whichever face sorts first, so either key would forget
 * the answer at the next index pass (`photos-people-and-pets.md` §6a F6c.1).
 */
data class FaceRejection(val path: String, val name: String, val region: FaceRegion)

/**
 * What this device's face grouping says about the unnamed group [groupId] and the name [name] —
 * never a fact, and never written: only the user's Yes writes a label.
 *
 * @property confident the two levels (maintainer, 2026-11-11). `true`: the grouping is sure, and
 *   People FOLDS the group into [name] on this device's screen alone — recomputed every read, no
 *   `MediaLabel`, nothing synced, so a wrong fold dies with the clustering that made it. `false`:
 *   People ASKS *"Is this [name]?"* on the unnamed card.
 * @property unlabelledPaths the group's photographs that do not carry [name] — what a Yes names, and
 *   what a fold adds to [name]'s card.
 * @property faces what a "No" records: the group's faces on those photographs (at most
 *   [MAX_REJECTED_FACES], see there). ⚠ Never the labelled ones — those photographs already say who
 *   is on them, and a group photo labelled [name] also holds this face's neighbour.
 */
data class NameSuggestion(
    val groupId: String,
    val name: String,
    val faces: List<GroupFace>,
    val unlabelledPaths: List<String> = faces.map { it.path }.distinct(),
    val confident: Boolean = false,
)

/**
 * Which unnamed groups to fold into a name on screen, which to ask about, and about whom (IN-6 F6c name suggestions,
 * `photos-people-and-pets.md` §6a *F6c-s as built*). Pure; the caller passes what one read saw.
 *
 * ⚠⚠⚠ **A suggestion is the FACE grouping's claim, never a label's.** A label is per PHOTOGRAPH:
 * a group selfie labelled "Anna" also holds her friend, so "this group touches a photo labelled
 * Anna" puts the friend's group in contact with her name. The rule therefore attributes a name to
 * a group by the group's own FACES (maintainer decision, 2026-11-11):
 *
 * 1. For each name N, every group is scored over the photographs labelled N: `sure` = how many it
 *    has a face on that is the only face the detector found in that photograph (unambiguous —
 *    that face IS N), `touched` = how many it has any face on.
 * 2. **The group that IS N** is the one with the most `sure`, then the most `touched` — and only
 *    if it has at least one `sure` face, or a strict majority (and at least two) of N's
 *    photographs. A tie at the top is nobody: two groups that are equally N are a question the
 *    face grouping has not answered.
 * 3. That group is asked about N only if it still holds a photograph not labelled N (so never
 *    one [peopleFrom] already folds into N), and none of its faces was rejected for N
 *    ([FaceRejection], matched by box overlap).
 * 4. **Conflict ⇒ no question:** the group is also the one that IS another name, or one of its
 *    unambiguous faces sits on a photograph labelled with another name.
 * 5. **Confident ⇒ a display-only fold instead of a question** ([NameSuggestion.confident]): the
 *    group has at least [CONFIDENT_MIN_SURE] unambiguous faces of N, EVERY unambiguous face of N in
 *    this read's groups is in it, and N's photographs are at least [CONFIDENT_MIN_LABELLED_SHARE]
 *    of the group's. ⚠ Crowded-only evidence is never confident. ⚠ A rejection blocks the fold as
 *    it blocks the question — the user said no.
 *
 * ⚠⚠ Every group is scored, folded ones included — a folded group can be the one that IS N, and
 *  then a group that merely touches one of N's group photos must lose to it rather than be asked.
 *
 * ⚠ Accepting writes the name on every photograph of the group — the ordinary `nameFaceGroup`;
 * the next read folds the group into N on every device, because the labels say so (§3.2).
 *
 * ⚠ Fails closed: a group without face detail (an older proxy) can never be attributed, so it is
 * never asked about. ⚠ No embedding centroid check: templates never leave the proxy, and group
 * membership is already the 0.44 clustering decision.
 *
 * @param faceGroups this device's groups, **with every face** — not `withoutEditedCovers`, which
 *   strips faces for avatar reasons and would hide the evidence
 * @param faceLabels `MediaLabel` rows for the face capability only
 * @return keyed by [FaceGroup.groupId]
 */
fun nameSuggestions(
    faceGroups: List<FaceGroup>,
    faceLabels: List<PhotoLabel>,
    rejections: List<FaceRejection>,
): Map<String, NameSuggestion> {
    if (faceGroups.isEmpty() || faceLabels.isEmpty()) return emptyMap()
    val pathsByName: Map<String, Set<String>> = faceLabels
        .groupBy({ it.label }, { it.path })
        .mapValues { (_, paths) -> paths.toHashSet() }
    val namesByPath: Map<String, Set<String>> = faceLabels
        .groupBy({ it.path }, { it.label })
        .mapValues { (_, names) -> names.toHashSet() }

    data class Score(val sure: Int, val touched: Int)

    // name -> how many groups hold an unambiguous face of that name (a solo face is in one group).
    val groupsWithSure: Map<String, Int> = pathsByName.mapValues { (_, labelled) ->
        faceGroups.count { group -> group.faces.any { it.facesInPhoto == 1 && it.path in labelled } }
    }

    fun FaceGroup.scoreFor(labelled: Set<String>): Score {
        val onLabelled = faces.filter { it.path in labelled }
        return Score(
            sure = onLabelled.filter { it.facesInPhoto == 1 }.map { it.path }.distinct().size,
            touched = onLabelled.map { it.path }.distinct().size,
        )
    }

    // name -> the one group that IS that name on this device, if any.
    val isName: Map<String, FaceGroup> = pathsByName.mapNotNull { (name, labelled) ->
        val scored = faceGroups
            .map { it to it.scoreFor(labelled) }
            .filter { (_, s) -> s.sure >= 1 || (s.touched >= 2 && 2 * s.touched > labelled.size) }
        val best = scored.maxWithOrNull(compareBy({ it.second.sure }, { it.second.touched })) ?: return@mapNotNull null
        // ⚠⚠ A tie is nobody — never the first in some order.
        if (scored.count { it.second == best.second } > 1) return@mapNotNull null
        name to best.first
    }.toMap()

    val namesOfGroup: Map<String, List<String>> = isName.entries.groupBy({ it.value.groupId }, { it.key })

    return isName.mapNotNull { (name, group) ->
        // ⚠ Conflict: the face grouping says this group is two people.
        if (namesOfGroup[group.groupId].orEmpty().size > 1) return@mapNotNull null
        // ⚠ Conflict: one of its own unambiguous faces is on a photograph labelled someone else.
        val otherSure = group.faces.any { face ->
            face.facesInPhoto == 1 && namesByPath[face.path].orEmpty().any { it != name }
        }
        if (otherSure) return@mapNotNull null
        val labelled = pathsByName.getValue(name)
        // ⚠ A group [peopleFrom] already folds by its labels needs no separate test: folded into N
        // it has nothing left to ask, and folded into another name it is a conflict above (its
        // faces are on that name's photographs).
        val asked = group.faces.filter { it.path !in labelled }
        if (asked.isEmpty()) return@mapNotNull null
        if (asked.any { face -> rejections.any { it.name == name && it.matches(face) } }) return@mapNotNull null
        val score = group.scoreFor(labelled)
        // ⚠⚠ Every unambiguous face of N is in this ONE group — a second group holding one means
        // the grouping has split N or merged someone into one of the two, and it is asked instead.
        // ⚠⚠ And the evidence must be more than a single photograph, and more than a sliver of the
        // group: one labelled solo photo inside a mis-merged group of hundreds would otherwise fold
        // all of them into N, and Confirm would label every one.
        val confident = groupsWithSure[name] == 1 &&
            score.sure >= CONFIDENT_MIN_SURE &&
            score.touched >= CONFIDENT_MIN_LABELLED_SHARE * group.filePaths.size
        group.groupId to NameSuggestion(
            groupId = group.groupId,
            name = name,
            faces = asked.sample(MAX_REJECTED_FACES),
            unlabelledPaths = group.filePaths.filter { it !in labelled },
            confident = confident,
        )
    }.toMap()
}

/**
 * Whether this rejection is about [face]: the same photograph, and boxes overlapping by at least
 * half of the smaller — the pipeline's own "the same face, two boxes" rule (§12.14's box
 * de-duplication; F6c.1's re-anchoring rule), so a re-index that moves the box a little keeps the
 * answer.
 */
fun FaceRejection.matches(face: GroupFace): Boolean {
    if (face.path != path) return false
    val a = region
    val b = face.region
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (w <= 0f || h <= 0f) return false
    val smaller = minOf(a.area, b.area)
    return smaller > 0f && w * h >= REJECTION_OVERLAP * smaller
}

/**
 * How many unambiguous photographs of N a group needs before it is folded rather than asked about.
 * ⚠ Two, not one: a single photograph is an anecdote — one stray face the grouping merged in is
 * enough to make it, and a fold is what a user may Confirm wholesale.
 */
const val CONFIDENT_MIN_SURE: Int = 2

/**
 * The share of a group's photographs that must already carry N before it is folded rather than
 * asked about. ⚠ A guard, not a measurement: one in five keeps a group that is mostly someone else
 * (a mis-merge, or a person photographed beside N for years) a QUESTION, and still folds the case
 * that opened this work — two named photographs in a group of up to ten.
 */
const val CONFIDENT_MIN_LABELLED_SHARE: Double = 0.2

/** Intersection over the smaller box — see [matches]. */
const val REJECTION_OVERLAP: Float = 0.5f

/**
 * How many of a group's faces one "No" records.
 *
 * ⚠ A bound, not a sample size anyone measured: ANY recorded face suppresses the question for the
 * group it lands in ([nameSuggestions] step 3), so a handful re-finds the group after a
 * re-clustering, and the bound keeps one answer — and the one IPC call carrying it — small for a
 * group of thousands. A group re-clustered so that none of these faces is in it is asked again:
 * the safe direction, one repeated question.
 */
const val MAX_REJECTED_FACES: Int = 32

/** At most [n], spread evenly over the list in path order — deterministic, so a retry sends the same. */
private fun List<GroupFace>.sample(n: Int): List<GroupFace> {
    val sorted = sortedWith(compareBy({ it.path }, { it.faceIndex }))
    if (sorted.size <= n) return sorted
    return List(n) { i -> sorted[i * sorted.size / n] }
}
