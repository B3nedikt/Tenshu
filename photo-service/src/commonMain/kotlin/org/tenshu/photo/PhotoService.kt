package org.tenshu.photo

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * What the user has *said* about their photos — albums, ordering, favourites (`photos-app.md`).
 *
 * ⚠⚠ **It stores no photos and knows nothing about files.** A photo is a `File` in `:file-service`,
 * and this service holds only statements *about* one, keyed by the file's `ContentId`. That line is
 * why it exists as its own service rather than as fields on `File` (`photos-app.md` PH-D1), and it
 * is what keeps `:file-service` — the platform's most central — free of a Photos concept. It also
 * means the two can be read independently: a `File` with no album is the normal case, and an
 * `AlbumEntry` whose file is gone is a repairable row rather than a corrupt one.
 *
 * Its relations are traversed with the standard chain DSL:
 *
 * ```
 * service.albumEntries
 *     .searchAll<AlbumEntry> { albumId equalTo id }
 *     .sortBy(AlbumEntry.Position)
 *     .flow()
 * ```
 *
 * ⚠ **Version 2** — v1 was the release baseline; v2 adds [PhotoEdit] (`photos-editing.md` ED-3) with
 * an empty step from 1, registered in `photoServiceCommonModule`. Every reshape bumps
 * `service(id, version =)` and ships its step (House Rule #9) — **not**
 * `TENSHU_STORAGE_FORMAT_VERSION`, which is `:core`'s alone, and moving it wipes every store.
 * ⚠⚠ Post-release, a bump with no step wipes every store: never delete a step.
 *
 * ⚠⚠ `SchemaShapeBaselineEnforcementTest` sees this service only because its `-common` module is
 * registered in `:system-test-utils`; without that registration the pin would be silently absent
 * rather than loudly wrong.
 */
interface PhotoService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Manual albums; see [Album]. Smart albums are queries and are not rows. */
    val albums: TList<Album>

    /** Album membership + ordering; see [AlbumEntry]. */
    val albumEntries: TList<AlbumEntry>

    /** Favourite / hidden marks; see [PhotoFlags]. */
    val photoFlags: TList<PhotoFlags>

    /** Where each trashed photo came from; see [TrashedPhoto]. */
    val trashedPhotos: TList<TrashedPhoto>

    /** Non-destructive edits, one per edited photo; see [PhotoEdit]. */
    val photoEdits: TList<PhotoEdit>

    companion object {
        const val ID = "tenshu/photo/service"

        // `maintained = true` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1): the proxy derives the element type, the array key
        // and the element's own key from what is declared here, so `:photo-service-proxy`
        // registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 = release baseline (`core-schema-evolution.md` Appendix A).
            // v2 = ED-3 (`photos-editing.md` §12): the `PhotoEdit` type and the `photoEdits`
            // relation. Additive — no existing field moves — so its step is empty.
            version = 2,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.ALBUMS,
                    type = TList.typeOf(Album.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.ALBUM_ENTRIES,
                    type = TList.typeOf(AlbumEntry.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.PHOTO_FLAGS,
                    type = TList.typeOf(PhotoFlags.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.TRASHED_PHOTOS,
                    type = TList.typeOf(TrashedPhoto.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.PHOTO_EDITS,
                    type = TList.typeOf(PhotoEdit.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val ALBUMS = "$ID/albums"
        const val ALBUM_ENTRIES = "$ID/albumEntries"
        const val PHOTO_FLAGS = "$ID/photoFlags"
        const val TRASHED_PHOTOS = "$ID/trashedPhotos"
        const val PHOTO_EDITS = "$ID/photoEdits"
    }
}
