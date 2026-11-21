package app.tenshu.photos.ui.screens.collections

import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.peopleFrom

/**
 * A pet card's id prefix (`photos-pets.md` PT-3d). ⚠⚠ What routes a naming to `namePetGroup` rather
 * than `nameFaceGroup`: a cat named as a person would land in People's Article 9 data. Minted only here,
 * and it survives the on-device snapshot, unlike any in-memory set.
 */
const val PET_ID_PREFIX: String = "pet:"

private const val PERSON_ID_PREFIX: String = "person:"

/**
 * Pets as cards: [peopleFrom]'s grouping and naming rules over pet groups and `petRecognition` labels —
 * named cards fold their groups exactly as people's do — re-addressed `pet:…` so no id can ever be taken
 * for a person's. A card whose photographs are all gone is dropped, as a person's is.
 */
fun petAlbums(
    petGroups: List<FaceGroup>,
    petLabels: List<PhotoLabel>,
    byPath: Map<String, Photo>,
): List<SmartAlbum> = peopleFrom(faceGroups = petGroups, faceLabels = petLabels).mapNotNull { pet ->
    val photos = pet.filePaths.mapNotNull { path -> byPath[path] }
    if (photos.isEmpty()) return@mapNotNull null
    SmartAlbum(
        id = pet.id.asPetId(),
        title = pet.name,
        subtitle = photoCount(photos.size),
        photos = photos,
        alternateIds = pet.alternateIds.map { it.asPetId() },
        coverFace = pet.cover,
    )
}

/**
 * PT-D3's one row: **named first** — people, then pets — then the unnamed, people then pets. ⚠ Each
 * list keeps its own order (largest group first for the unnamed), so adding pets never reshuffles people.
 */
fun mixPeopleAndPets(people: List<SmartAlbum>, pets: List<SmartAlbum>): List<SmartAlbum> {
    val (namedPeople, unnamedPeople) = people.partition { it.title.isNotBlank() }
    val (namedPets, unnamedPets) = pets.partition { it.title.isNotBlank() }
    return namedPeople + namedPets + unnamedPeople + unnamedPets
}

private fun String.asPetId(): String =
    if (startsWith(PERSON_ID_PREFIX)) PET_ID_PREFIX + removePrefix(PERSON_ID_PREFIX) else PET_ID_PREFIX + this
