package app.tenshu.photos.ui.screens.collections

import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.ui.screens.albumdetail.PersonHeader
import app.tenshu.photos.ui.screens.albumdetail.companionsFor

/**
 * What a derived section's page shows beyond its photographs when it is a PERSON — the header, the
 * naming action and its verdicts (IN-6 F3b, F6a, F6b). Compose's `Destination.Smart` and the SwiftUI
 * person page both read it, so neither decides who is a person or whose verdict a dialog is
 * (`ios-face-grouping.md` F7; PIO-D2).
 *
 * @property header `null` for every section that is not a person — a place, a year, a Thing.
 * @property nameId the id `CollectionsViewModel.nameGroup` is called with, or `null` where naming is
 *   absent: not a person, or a snapshot card whose photographs are not known yet.
 * @property nameFailure a naming verdict that did not save, for THIS person only.
 * @property unfinishedName the NAME an interrupted naming of this person was writing.
 * @property suggestedName *"Is this Anna?"* (IN-6 F6c name suggestions) — the name to ask about, or
 *   `null`. Answered with `CollectionsViewModel.acceptSuggestion` / `rejectSuggestion` on [nameId].
 *   ⚠ Only where naming is offered: a Yes IS a naming.
 * @property unconfirmedCount how many of a NAMED person's photographs are on the page only because
 *   this device's face grouping folded a group into them — unlabelled, unsynced, `0` for none.
 *   Answered like a question: Confirm (`acceptSuggestion`) labels them, Not (`rejectSuggestion`)
 *   unfolds them.
 * @property suggestionRejectFailed the last "No" on this person could not be recorded.
 */
data class PersonPage(
    val header: PersonHeader?,
    val nameId: String?,
    val nameFailure: NamingOutcome?,
    val unfinishedName: String?,
    val suggestedName: String? = null,
    val unconfirmedCount: Int = 0,
    val suggestionRejectFailed: Boolean = false,
)

/**
 * [page] (the route [smartId]'s `smartPage`) as a person's page.
 *
 * ⚠⚠ Asked of the STATE, never of the id's spelling: `people` is the list that knows, and matching
 * on a `"person:"` prefix would make a naming button appear on any section spelled that way.
 * ⚠⚠ Asked of the RESOLVED album, not the raw route id: after a first naming the route still holds
 * `person:<groupId>` while the person's primary id is `person:name:<name>`.
 * ⚠⚠ A verdict and a retry are matched against EVERY address this person answers to: `nameGroup` is
 * called with the resolved primary id while the route can hold an alias.
 * ⚠ The "With…" selection is keyed on the ROUTE, so it survives a naming that re-keys this person.
 */
fun CollectionsUiState.personPage(smartId: String, page: SmartPage): PersonPage {
    val album = page.album
    val addresses: Set<String> = setOfNotNull(smartId, album?.id) + album?.alternateIds.orEmpty()
    val isPerson = album != null && people.any { it.id == album.id }
    val photosKnown = !page.loading && !page.failed
    val nameId = album?.takeIf { isPerson && photosKnown }?.id
    return PersonPage(
        header = if (isPerson && album != null) {
            PersonHeader(cover = album.coverFace, companions = companionsFor(album, people), key = smartId)
        } else {
            null
        },
        nameId = nameId,
        nameFailure = nameFailure?.takeIf { nameFailureFor in addresses },
        unfinishedName = namingIncompleteFor.firstOrNull { it.smartId in addresses }?.name,
        // ⚠ Keyed by the resolved primary id — the one a Yes names through — and never on a card
        // that already carries a name.
        suggestedName = nameId?.let { id -> nameSuggestions[id] }
            ?.takeIf { !it.confident && album?.title.isNullOrBlank() }?.name,
        unconfirmedCount = nameId?.let { id -> nameSuggestions[id] }
            ?.takeIf { it.confident && album?.title == it.name }
            ?.let { fold -> fold.unlabelledPaths.toHashSet().let { paths -> album?.photos?.count { it.path in paths } } }
            ?: 0,
        suggestionRejectFailed = nameId != null && suggestionRejectFailedFor == nameId,
    )
}
