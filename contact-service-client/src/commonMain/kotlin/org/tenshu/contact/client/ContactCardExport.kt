package org.tenshu.contact.client

import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.Photos
import org.tenshu.contact.common.Reassembly
import org.tenshu.contact.common.photoRefsIn
import org.tenshu.contact.common.reassembleVCard
import org.tenshu.contact.sourceKey
import org.tenshu.core.TError
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.fold
import org.tenshu.core.resolve

/** What [fullVcard] could produce. */
sealed interface FullCardRead {
    /** The card as it was imported or last saved — byte-exact, photos and all. */
    data class Ok(val card: String) : FullCardRead

    /**
     * ⚠⚠ The card's big photos are on the content plane and could not be brought here (offline,
     * or the relay has freed them). **Fail the export** — never export the stored card in
     * their place, which would ship a placeholder line instead of the photo.
     */
    data object PhotosUnavailable : FullCardRead

    /** The read itself failed — a failure, never "nothing to export". */
    data class Failed(val error: TError) : FullCardRead
}

/**
 * The full card behind one source record, for export, share and re-encode (`contacts-app.md`
 * §9.10): [storedCard] (its `rawVcard`) with every relocated `PHOTO` line put back.
 *
 * A card that moved nothing — every card with only small photos, or none — is returned as it is
 * with no read at all. One with placeholders reads [ContactSource.Photos] through a **Maybe
 * `resolve()`** terminal: only Maybe/stream terminals materialise an on-demand property, so a blob
 * not yet on this device is fetched from the relay before the entity crosses IPC (`getAll()`
 * would read "no photos" for ever on a second device).
 */
suspend fun ContactService.fullVcard(sourceKey: String, storedCard: String): FullCardRead {
    if (photoRefsIn(storedCard).isEmpty()) return FullCardRead.Ok(storedCard)
    return sources
        .search<ContactSource> { this.sourceKey equalTo sourceKey }
        .select {
            fields(ContactSource.Field.SOURCE_KEY, ContactSource.Field.RAW_VCARD)
            relations(ContactSource.Photos)
        }
        .resolve()
        .fold(
            onSuccess = { entity ->
                // ⚠ Reassembled from the card read here, beside its photos — not from [storedCard],
                // which may be an older version than the photos the relay just served.
                val card = entity?.getFieldValue<org.tenshu.core.TString>(ContactSource.Field.RAW_VCARD)?.get()
                    ?: return@fold FullCardRead.PhotosUnavailable
                when (val reassembled = reassembleVCard(card, entity.get(ContactSource.Photos)?.get())) {
                    is Reassembly.Complete -> FullCardRead.Ok(reassembled.card)
                    Reassembly.PhotosUnavailable, Reassembly.PhotosMismatch -> FullCardRead.PhotosUnavailable
                }
            },
            onFailure = { error -> FullCardRead.Failed(error) },
        )
}
