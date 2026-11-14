package org.tenshu.photo.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.schema.migration.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.TrashedPhoto
import org.tenshu.photo.TrashedPhotoDataIdProvider
import org.tenshu.photo.common.impl.TrashedPhotoDataIdProviderImpl
import org.tenshu.photo.common.impl.TrashedPhotoDto
import org.tenshu.photo.common.impl.TrashedPhotoRo
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumDataIdProvider
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.AlbumEntryDataIdProvider
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.ClearPhotoEdit
import org.tenshu.photo.PhotoEdit
import org.tenshu.photo.PhotoEditDataIdProvider
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.SetPhotoEdit
import org.tenshu.photo.PhotoFlagsDataIdProvider
import org.tenshu.photo.PhotoService
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.SetPhotoFlags
import org.tenshu.photo.common.impl.AlbumDataIdProviderImpl
import org.tenshu.photo.common.impl.AlbumDto
import org.tenshu.photo.common.impl.AlbumEntryDataIdProviderImpl
import org.tenshu.photo.common.impl.AlbumEntryDto
import org.tenshu.photo.common.impl.AlbumEntryRo
import org.tenshu.photo.common.impl.AlbumRo
import org.tenshu.photo.common.impl.PhotoEditDataIdProviderImpl
import org.tenshu.photo.common.impl.PhotoEditDto
import org.tenshu.photo.common.impl.PhotoEditRo
import org.tenshu.photo.common.impl.PhotoFlagsDataIdProviderImpl
import org.tenshu.photo.common.impl.PhotoFlagsDto
import org.tenshu.photo.common.impl.PhotoFlagsRo

/**
 * Registers the photo-service schema graph (`photos-app.md` PH-2).
 *
 * ⚠ Every composite registers **both** a `dtoFactory` and a `roFactory`. The RO half is easy to
 * forget because nothing fails until a chain projects the type, at which point the read dies as an
 * opaque `error/internal` naming nothing — the `GetWorkoutStreamsRo` lesson, restated in each
 * `*Ro` here.
 *
 * ⚠⚠ **The migration chain starts at v1, the release baseline** (HR #9). Every reshape owes both a
 * bump and a step, and post-release a step is never deleted — a bump with no step wipes the store.
 */
val photoServiceCommonModule = tModule {

    composite(
        interfaceClass = Album::class,
        dataSchema = Album.Schema,
        dtoFactory = ::AlbumDto,
        roFactory = { chain, ctx ->
            AlbumRo(requestChain = chain, context = ctx, schema = Album.Schema)
        }
    )

    composite(
        interfaceClass = AlbumEntry::class,
        dataSchema = AlbumEntry.Schema,
        dtoFactory = ::AlbumEntryDto,
        roFactory = { chain, ctx ->
            AlbumEntryRo(requestChain = chain, context = ctx, schema = AlbumEntry.Schema)
        }
    )

    composite(
        interfaceClass = PhotoFlags::class,
        dataSchema = PhotoFlags.Schema,
        dtoFactory = ::PhotoFlagsDto,
        roFactory = { chain, ctx ->
            PhotoFlagsRo(requestChain = chain, context = ctx, schema = PhotoFlags.Schema)
        }
    )

    composite(
        interfaceClass = TrashedPhoto::class,
        dataSchema = TrashedPhoto.Schema,
        dtoFactory = ::TrashedPhotoDto,
        roFactory = { chain, ctx ->
            TrashedPhotoRo(requestChain = chain, context = ctx, schema = TrashedPhoto.Schema)
        }
    )

    trait(
        dataSchema = TrashedPhoto.Schema,
        traitSchema = TrashedPhotoDataIdProvider.Schema,
        traitFactory = { context, schema ->
            TrashedPhotoDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = Album.Schema,
        traitSchema = AlbumDataIdProvider.Schema,
        traitFactory = { context, schema ->
            AlbumDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = AlbumEntry.Schema,
        traitSchema = AlbumEntryDataIdProvider.Schema,
        traitFactory = { context, schema ->
            AlbumEntryDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = PhotoFlags.Schema,
        traitSchema = PhotoFlagsDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PhotoFlagsDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = PhotoEdit::class,
        dataSchema = PhotoEdit.Schema,
        dtoFactory = ::PhotoEditDto,
        roFactory = { chain, ctx ->
            PhotoEditRo(requestChain = chain, context = ctx, schema = PhotoEdit.Schema)
        }
    )

    trait(
        dataSchema = PhotoEdit.Schema,
        traitSchema = PhotoEditDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PhotoEditDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    service(PhotoService.Schema)

    // HR #9: v2 (ED-3, `photos-editing.md` §12) — the `PhotoEdit` type and the `photoEdits`
    // relation. Empty because it is additive: a new type and a new relation, no existing field
    // moved, so a v1 row reads as it was. The registration is what keeps a v1 store migrating
    // instead of wiping (`ServiceMigrationChainsReachTheirVersionTest`). ⚠ Never delete it.
    schemaMigration(schemaMigration(PhotoService.ID, fromVersion = 1) {})

    operation(SaveAlbum.Schema)
    operation(DeleteAlbum.Schema)
    operation(AddToAlbum.Schema)
    operation(RemoveFromAlbum.Schema)
    operation(SetPhotoFlags.Schema)
    operation(RecordTrashedPhoto.Schema)
    operation(ClearTrashedPhoto.Schema)
    operation(SetPhotoEdit.Schema)
    operation(ClearPhotoEdit.Schema)
}
