package org.tenshu.photo.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.SetPhotoFlags
import org.tenshu.photo.proxy.impl.AddToAlbumImplementation
import org.tenshu.photo.proxy.impl.ClearTrashedPhotoImplementation
import org.tenshu.photo.proxy.impl.RecordTrashedPhotoImplementation
import org.tenshu.photo.proxy.impl.DeleteAlbumImplementation
import org.tenshu.photo.proxy.impl.RemoveFromAlbumImplementation
import org.tenshu.photo.proxy.impl.SaveAlbumImplementation
import org.tenshu.photo.proxy.impl.SetPhotoFlagsImplementation

/**
 * @param nowEpochMs the clock an album's `createdAt` is stamped from. ⚠ A constructor parameter
 *   with a default, the `calendarServiceProxyImplementationFactory` shape — not a `TContext`
 *   component lookup, which `docs/agent.md` rules against growing.
 */
fun photoServiceProxyImplementationFactory(
    id: String,
    nowEpochMs: () -> Long = { 0L },
): OperationImplementation? = when (id) {
    SaveAlbum.ID -> SaveAlbumImplementation(nowEpochMs)
    DeleteAlbum.ID -> DeleteAlbumImplementation()
    AddToAlbum.ID -> AddToAlbumImplementation()
    RemoveFromAlbum.ID -> RemoveFromAlbumImplementation()
    SetPhotoFlags.ID -> SetPhotoFlagsImplementation()
    RecordTrashedPhoto.ID -> RecordTrashedPhotoImplementation(nowEpochMs)
    ClearTrashedPhoto.ID -> ClearTrashedPhotoImplementation()
    else -> null
}
