package org.tenshu.photo.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.ClearPhotoEdit
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.SetPhotoEdit
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.SetPhotoFlags
import org.tenshu.photo.proxy.impl.AddToAlbumImplementation
import org.tenshu.photo.proxy.impl.ClearPhotoEditImplementation
import org.tenshu.photo.proxy.impl.ClearTrashedPhotoImplementation
import org.tenshu.photo.proxy.impl.SetPhotoEditImplementation
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
    /**
     * The deletion counterpart: ⚠⚠ `null` makes every delete here device-local — [DeleteAlbum],
     * [RemoveFromAlbum], [ClearTrashedPhoto] and [ClearPhotoEdit] drop the row on this device and it
     * stays on every other one (an album deleted on the phone is still on the tablet).
     * `SyncObserver.onDataDeleted` emits nothing; a tombstone is the op's own duty. Acceptable in a
     * sync-less harness; never in production.
     */
    tombstoneEmitter: org.tenshu.sync.common.TombstoneEmitter? = null,
): OperationImplementation? = when (id) {
    SaveAlbum.ID -> SaveAlbumImplementation(nowEpochMs)
    DeleteAlbum.ID -> DeleteAlbumImplementation(tombstoneEmitter)
    AddToAlbum.ID -> AddToAlbumImplementation()
    RemoveFromAlbum.ID -> RemoveFromAlbumImplementation(tombstoneEmitter)
    SetPhotoFlags.ID -> SetPhotoFlagsImplementation()
    RecordTrashedPhoto.ID -> RecordTrashedPhotoImplementation(nowEpochMs)
    ClearTrashedPhoto.ID -> ClearTrashedPhotoImplementation(tombstoneEmitter)
    SetPhotoEdit.ID -> SetPhotoEditImplementation(nowEpochMs)
    ClearPhotoEdit.ID -> ClearPhotoEditImplementation(tombstoneEmitter)
    else -> null
}
