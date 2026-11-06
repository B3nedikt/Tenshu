package org.tenshu.photo.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.PhotoService
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.SetPhotoFlags
import org.tenshu.photo.client.impl.AddToAlbumRo
import org.tenshu.photo.client.impl.ClearTrashedPhotoRo
import org.tenshu.photo.client.impl.RecordTrashedPhotoRo
import org.tenshu.photo.client.impl.DeleteAlbumRo
import org.tenshu.photo.client.impl.PhotoServiceRo
import org.tenshu.photo.client.impl.RemoveFromAlbumRo
import org.tenshu.photo.client.impl.SaveAlbumRo
import org.tenshu.photo.client.impl.SetPhotoFlagsRo

/**
 * Client-side registration for the photo-service operation surface (`photos-app.md` PH-2).
 *
 * ⚠⚠ Every op needs its RO registered here, not just the schema in `:photo-service`. Dispatch goes
 * through the *client* module's `roFactory`, and a missing registration does not fail at startup —
 * it surfaces later as an opaque `error/internal` on whichever call happens to need it first (the
 * `GetWorkoutStreamsRo` lesson, and the same one `FileCategoryOfRo` restated in PH-1).
 */
val photoServiceClientModule = tModule {

    service(
        interfaceClass = PhotoService::class,
        serviceSchema = PhotoService.Schema,
        roFactory = ::PhotoServiceRo,
    )

    operation(
        interfaceClass = SaveAlbum::class,
        protocolSchema = SaveAlbum.Schema,
        roFactory = ::SaveAlbumRo,
    )

    operation(
        interfaceClass = DeleteAlbum::class,
        protocolSchema = DeleteAlbum.Schema,
        roFactory = ::DeleteAlbumRo,
    )

    operation(
        interfaceClass = AddToAlbum::class,
        protocolSchema = AddToAlbum.Schema,
        roFactory = ::AddToAlbumRo,
    )

    operation(
        interfaceClass = RemoveFromAlbum::class,
        protocolSchema = RemoveFromAlbum.Schema,
        roFactory = ::RemoveFromAlbumRo,
    )

    operation(
        interfaceClass = SetPhotoFlags::class,
        protocolSchema = SetPhotoFlags.Schema,
        roFactory = ::SetPhotoFlagsRo,
    )

    operation(
        interfaceClass = RecordTrashedPhoto::class,
        protocolSchema = RecordTrashedPhoto.Schema,
        roFactory = ::RecordTrashedPhotoRo,
    )

    operation(
        interfaceClass = ClearTrashedPhoto::class,
        protocolSchema = ClearTrashedPhoto.Schema,
        roFactory = ::ClearTrashedPhotoRo,
    )
}
