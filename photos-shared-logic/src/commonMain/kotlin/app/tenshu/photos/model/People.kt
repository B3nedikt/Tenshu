package app.tenshu.photos.model

/**
 * One person as the Collections tab shows them (IN-6 F3b) — a name, or a group nobody has named.
 *
 * ⚠⚠ **Two independent sources, and the union is the point.** A *face group* is device-local and
 * exists only where a model has run; a *name* is a synced `MediaLabel` and exists on every device
 * the user owns. `photos-people-and-pets.md` §3.2 is explicit that the synced label is what gives
 * cross-device consistency — so a laptop with no face backend must still show Anna, from her labels
 * alone, or the durable half of IN-D5 is invisible exactly where it was supposed to pay off.
 */
data class PersonEntry(
    /** Stable for a named person; ephemeral for an unnamed group. See [peopleFrom]. */
    val id: String,
    /** Empty when nobody has named them. */
    val name: String,
    val filePaths: List<String>,
    /**
     * Other ids that address this same person (IN-6 F3b).
     *
     * ⚠⚠ **A person can absorb more than one cluster, and every one of them was a route.**
     * Clustering splits people; naming a second cluster with an existing name folds it into that
     * person, and the card the user was looking at — addressed by THAT cluster's id — would
     * otherwise resolve to nothing the moment the name took effect. The screen going blank on a
     * successful naming has been this feature's most persistent defect; carrying the aliases is
     * what stops it recurring for every group beyond the first.
     */
    val alternateIds: List<String> = emptyList(),
    /** The face the person header shows (IN-6 F6a); `null` falls back to the whole photograph. */
    val cover: FaceCover? = null,
)

/**
 * Merge face groups and person labels into the People list.
 *
 * ⚠⚠⚠ **A named person is EXACTLY their labelled photographs. Nothing is inferred.**
 *
 * This replaced an overlap-matching rule — votes, then mutual-best, then majority coverage — that
 * tried to work out which cluster a name "meant" and merged that cluster's unlabelled members into
 * the person. It was wrong six times running, each time in a new shape, and each fix was right
 * about the case it was handed and wrong about the class it belonged to:
 *
 * - one shared photograph let a name take another person's cluster;
 * - on a receiving device with no competing cluster, mutual-best had nothing to prefer;
 * - a majority bar still claimed a cluster where most photographs genuinely show two people;
 * - suppression measured against the union deleted a third person on two others' photographs.
 *
 * ⭐ They share one root: **inferring identity from overlap is ambiguous, and every threshold is a
 * guess some real library falsifies.** The rule below has no threshold and no inference, so the
 * class is gone rather than pushed one case further out.
 *
 * ⚠ **The deliberate loss:** a photograph this device clusters with Anna but nobody has labelled is
 * **not** on Anna's card. It appears under the unnamed cluster it belongs to, where naming adds it
 * — which is the honest rendering, because that photograph genuinely carries no name. Recovering
 * that nicety is what cost six rounds; it is not worth a seventh.
 *
 * ⚠ A group is hidden only when **every** photograph in it already belongs to one named person — an
 * exact test, never a proportion. Such a group also lends that person its id, which is what keeps
 * an open `Destination.Smart` alive across a naming: the route holds an id and naming refreshes.
 *
 * ⚠⚠ Two different people who share a name are ONE person here, because the durable artifact is the
 * name and nothing else (IN-D5). `photos-people-and-pets.md` §9 records that as a deliberate
 * deferral: an optional `personId` on `MediaLabel` fixes it and is purely additive, and what it
 * waits on is the consent story. Do not "fix" it here by inventing a local identity — that puts a
 * key in the UI layer the synced rows do not carry, and two devices would disagree about who is who.
 *
 * @param faceGroups what this device clusters — may be empty on a device with no model
 * @param faceLabels `MediaLabel` rows for the face capability only; the caller filters
 */
fun peopleFrom(
    faceGroups: List<FaceGroup>,
    faceLabels: List<PhotoLabel>,
): List<PersonEntry> {
    val pathsByName = faceLabels
        .groupBy({ it.label }, { it.path })
        .mapValues { (_, paths) -> paths.distinct() }

    /** Whether every photograph in [group] carries this name. ⚠ Empty groups never qualify. */
    fun FaceGroup.isEntirely(owned: Set<String>): Boolean =
        filePaths.isNotEmpty() && filePaths.all { it in owned }

    // ⚠⚠⚠ A cluster may address exactly ONE person. Making the primary id unique was half
    // the fix: when Anna and Bob appear together in every photograph they both fully cover the same
    // clusters, so both would list `person:g1` as an alias — and the route lookup takes the first
    // match, so an existing Bob route silently opens Anna. Assigned to the first name in the sorted
    // order, which is deterministic and enough: a second person named from someone's card is being
    // named while standing on THAT card's route, so they never needed the cluster's id.
    val aliasOwner = mutableMapOf<String, String>() // groupId -> name
    pathsByName.entries.sortedBy { it.key }.forEach { (name, labelled) ->
        val owned = labelled.toHashSet()
        faceGroups.filter { it.isEntirely(owned) }.forEach { group ->
            aliasOwner.getOrPut(group.groupId) { name }
        }
    }

    val named = pathsByName.entries
        .sortedBy { it.key }
        .map { (name, labelled) ->
            // ⚠⚠⚠ **The primary id comes from the NAME, never from a cluster.** Two people
            // who appear in every photograph together cover exactly the same groups, so a
            // cluster-derived id gives them the SAME id — and `CollectionsScreen` keys its lazy
            // grid on it, which is a duplicate-key crash rather than a wrong card. A name is
            // unique across this list by construction, because two people sharing a name are
            // already one entry here (see the note above).
            //
            // ⚠ The clusters become aliases instead, which is all the route needs: the lookup
            // matches any id a person answers to, so an open `person:<groupId>` survives the
            // naming that folds that cluster into a name.
            val lenderIds = aliasOwner.filterValues { it == name }.keys
            val lenders = lenderIds.map { "person:$it" }
            // ⚠ F6a: the cover comes only from clusters this name ENTIRELY covers (its lenders),
            // because only there is every face this person's. A labelled photo with two faces
            // cannot say which is hers — §6a's face-level gap — so it never supplies the cover.
            val cover = faceGroups.filter { it.groupId in lenderIds }.flatMap { it.faces }.largestFace()
            PersonEntry(
                id = "person:name:$name",
                name = name,
                filePaths = labelled,
                // ⚠ Sorted so the list never depends on map iteration order.
                alternateIds = lenders.sorted(),
                cover = cover,
            )
        }

    val unnamed = faceGroups
        .filterNot { group -> pathsByName.values.any { group.isEntirely(it.toHashSet()) } }
        .map { group ->
            PersonEntry(
                id = "person:${group.groupId}",
                name = "",
                filePaths = group.filePaths,
                cover = group.faces.largestFace(),
            )
        }

    return named + unnamed
}
