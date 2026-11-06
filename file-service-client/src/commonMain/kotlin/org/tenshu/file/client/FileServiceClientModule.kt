package org.tenshu.file.client

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.impl.EntityRo
import org.tenshu.core.common.impl.array.StreamRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.file.system.ReplaceSourceFileBg
import org.tenshu.core.file.system.UploadSourceFileBg
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.common.module.type
import org.tenshu.core.file.File
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.FileCategoryOf
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.HasLocalData
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.ListPlacements
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.file.system.PhotosNear
import org.tenshu.core.file.system.ListSharedFolder
import org.tenshu.core.file.system.MountSharedRealm
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.file.system.ReadSharedFile
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.ReplaceFileBg
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.system.UploadFileBg
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.file.client.impl.CreateFolderRo
import org.tenshu.file.client.impl.DeleteFileRo
import org.tenshu.file.client.impl.EvictFileBytesRo
import org.tenshu.file.client.impl.HasLocalDataRo
import org.tenshu.file.client.impl.FilePathRo
import org.tenshu.file.client.impl.FileServiceRo
import org.tenshu.file.client.impl.FileSystemRo
import org.tenshu.file.client.impl.GetDataRo
import org.tenshu.file.client.impl.GetPreviewRo
import org.tenshu.file.client.impl.FileCategoryOfRo
import org.tenshu.file.client.impl.GetStoredThumbnailRo
import org.tenshu.file.client.impl.MoveFileRo
import org.tenshu.file.client.impl.ReplaceFileBgRo
import org.tenshu.file.client.impl.ReplaceFileRo
import org.tenshu.file.client.impl.ListPlacementsRo
import org.tenshu.file.client.impl.PhotosCapturedBetweenRo
import org.tenshu.file.client.impl.PhotosNearRo
import org.tenshu.file.client.impl.ListSharedFolderRo
import org.tenshu.file.client.impl.MountSharedRealmRo
import org.tenshu.file.client.impl.EnsureThumbnailClassRo
import org.tenshu.file.client.impl.ReadSharedFileRo
import org.tenshu.file.client.impl.ShareFolderIntoRealmRo
import org.tenshu.file.client.impl.UploadFileBgRo
import org.tenshu.file.client.impl.UploadFileRo
import org.tenshu.file.client.impl.share.RevokeShareLinkRo
import org.tenshu.file.client.impl.share.ShareLinkRo
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule

val fileServiceClientModule = tModule {
    // ROs for the by-reference upload/replace ops — alongside the rest for the same reason the
    // schemas are: op parameter objects are client-serialized composites, so a separate module
    // keeps them out of the shape baseline too.
    operation(
        interfaceClass = UploadSourceFile::class,
        protocolSchema = UploadSourceFile.Schema,
        roFactory = { chain, context -> UploadSourceFileRo(chain, context) }
    )
    operation(
        interfaceClass = ReplaceSourceFile::class,
        protocolSchema = ReplaceSourceFile.Schema,
        roFactory = { chain, context -> ReplaceSourceFileRo(chain, context) }
    )
    operation(
        interfaceClass = UploadSourceFileBg::class,
        protocolSchema = UploadSourceFileBg.Schema,
        roFactory = { chain, context -> UploadSourceFileBgRo(chain, context) }
    )
    operation(
        interfaceClass = ReplaceSourceFileBg::class,
        protocolSchema = ReplaceSourceFileBg.Schema,
        roFactory = { chain, context -> ReplaceSourceFileBgRo(chain, context) }
    )
    include(notificationServiceClientModule)
    include(fileServiceCommonModule)

    type(
        interfaceClass = FileAddedNotification::class,
        dataSchema = FileAddedNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                FileAddedNotification.Schema
            ), FileAddedNotification {
                override val schema: CompositeSchema get() = FileAddedNotification.Schema
            }
        }
    )

    type(
        interfaceClass = FileDeletedNotification::class,
        dataSchema = FileDeletedNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                FileDeletedNotification.Schema
            ), FileDeletedNotification {
                override val schema: CompositeSchema get() = FileDeletedNotification.Schema
            }
        }
    )

    type(
        interfaceClass = FolderAddedNotification::class,
        dataSchema = FolderAddedNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                FolderAddedNotification.Schema
            ), FolderAddedNotification {
                override val schema: CompositeSchema get() = FolderAddedNotification.Schema
            }
        }
    )

    type(
        interfaceClass = FolderDeletedNotification::class,
        dataSchema = FolderDeletedNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                FolderDeletedNotification.Schema
            ), FolderDeletedNotification {
                override val schema: CompositeSchema get() = FolderDeletedNotification.Schema
            }
        }
    )

    type(
        interfaceClass = ChangeLostNotification::class,
        dataSchema = ChangeLostNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                ChangeLostNotification.Schema
            ), ChangeLostNotification {
                override val schema: CompositeSchema get() = ChangeLostNotification.Schema
            }
        }
    )

    type(
        interfaceClass = File::class,
        dataSchema = File.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<File>(chain, ctx, File.Schema), File {
                override val schema: CompositeSchema get() = File.Schema
            }
        }
    )
    type(
        interfaceClass = Folder::class,
        dataSchema = Folder.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<Folder>(chain, ctx, Folder.Schema), Folder {
                override val schema: CompositeSchema get() = Folder.Schema
            }
        }
    )

    // Shared-realm file (drive-shared-realm-file-model.md §6 Phase 3c): a typed RO
    // so the browse chain `…select { relations(SharedFile.Children) }.search<SharedFile> { … }`
    // resolves field predicates + the children entity-relation. Mirrors File/Folder.
    type(
        interfaceClass = SharedFile::class,
        dataSchema = SharedFile.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<SharedFile>(chain, ctx, SharedFile.Schema), SharedFile {
                override val schema: CompositeSchema get() = SharedFile.Schema
            }
        }
    )

    // The connector (drive-shared-realm-file-model.md §6 Phase 3c.2): a typed RO so
    // the `listPlacements` op's `TList<Placement>` materialises its elements as
    // `Placement`. Mirrors the SharedFile RO above.
    type(
        interfaceClass = Placement::class,
        dataSchema = Placement.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<Placement>(chain, ctx, Placement.Schema), Placement {
                override val schema: CompositeSchema get() = Placement.Schema
            }
        }
    )

    type(
        interfaceClass = FilePath::class,
        dataSchema = FilePath.Schema,
        roFactory = ::FilePathRo
    )

    // Typed RO for the abstract FileModification parent so the chain DSL can
    // build the predicate receiver for `modifications.filter<FileModification>
    // { … }` (the per-file history accessor). Mirrors the notification ROs above
    // — a CompositeRo that also implements the interface so `filter`'s cast to
    // the element type succeeds and `fileId`/etc. resolve as field references.
    type(
        interfaceClass = FileModification::class,
        dataSchema = FileModification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, FileModification.Schema), FileModification {
                override val schema: CompositeSchema get() = FileModification.Schema
            }
        }
    )

    operation(
        interfaceClass = CreateFolder::class,
        protocolSchema = CreateFolder.Schema,
        roFactory = ::CreateFolderRo
    )
    operation(
        interfaceClass = CreateFolder::class,
        protocolSchema = CreateFolder.Schema,
        roFactory = ::CreateFolderRo
    )
    operation(
        interfaceClass = DeleteFile::class,
        protocolSchema = DeleteFile.Schema,
        roFactory = ::DeleteFileRo
    )
    operation(
        interfaceClass = DeleteFile::class,
        protocolSchema = DeleteFile.Schema,
        roFactory = ::DeleteFileRo
    )
    operation(
        interfaceClass = EvictFileBytes::class,
        protocolSchema = EvictFileBytes.Schema,
        roFactory = ::EvictFileBytesRo
    )
    operation(
        interfaceClass = HasLocalData::class,
        protocolSchema = HasLocalData.Schema,
        roFactory = ::HasLocalDataRo
    )
    operation(
        interfaceClass = MoveFile::class,
        protocolSchema = MoveFile.Schema,
        roFactory = ::MoveFileRo
    )
    operation(
        interfaceClass = MoveFile::class,
        protocolSchema = MoveFile.Schema,
        roFactory = ::MoveFileRo
    )
    operation(
        interfaceClass = UploadFile::class,
        protocolSchema = UploadFile.Schema,
        roFactory = ::UploadFileRo
    )

    operation(
        interfaceClass = UploadFile::class,
        protocolSchema = UploadFile.Schema,
        roFactory = ::UploadFileRo
    )
    operation(
        interfaceClass = ReplaceFile::class,
        protocolSchema = ReplaceFile.Schema,
        roFactory = ::ReplaceFileRo
    )
    // Background (cancellable Layer-2) folder-upload variants —
    // docs/proposals/drive-folder-upload.md §3a.
    operation(
        interfaceClass = UploadFileBg::class,
        protocolSchema = UploadFileBg.Schema,
        roFactory = ::UploadFileBgRo
    )
    operation(
        interfaceClass = ReplaceFileBg::class,
        protocolSchema = ReplaceFileBg.Schema,
        roFactory = ::ReplaceFileBgRo
    )
    operation(
        interfaceClass = Stream::class,
        protocolSchema = Stream.Schema,
        roFactory = { chain, context ->
            StreamRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = ShareFolderIntoRealm::class,
        protocolSchema = ShareFolderIntoRealm.Schema,
        roFactory = ::ShareFolderIntoRealmRo
    )
    operation(
        interfaceClass = ListSharedFolder::class,
        protocolSchema = ListSharedFolder.Schema,
        roFactory = ::ListSharedFolderRo
    )
    operation(
        interfaceClass = ReadSharedFile::class,
        protocolSchema = ReadSharedFile.Schema,
        roFactory = ::ReadSharedFileRo
    )
    operation(
        interfaceClass = EnsureThumbnailClass::class,
        protocolSchema = EnsureThumbnailClass.Schema,
        roFactory = ::EnsureThumbnailClassRo
    )
    operation(
        interfaceClass = MountSharedRealm::class,
        protocolSchema = MountSharedRealm.Schema,
        roFactory = ::MountSharedRealmRo
    )
    operation(
        interfaceClass = ListPlacements::class,
        protocolSchema = ListPlacements.Schema,
        roFactory = ::ListPlacementsRo
    )
    operation(
        interfaceClass = PhotosCapturedBetween::class,
        protocolSchema = PhotosCapturedBetween.Schema,
        roFactory = ::PhotosCapturedBetweenRo
    )

    operation(
        interfaceClass = PhotosNear::class,
        protocolSchema = PhotosNear.Schema,
        roFactory = ::PhotosNearRo
    )
    operation(
        interfaceClass = GetData::class,
        protocolSchema = GetData.Schema,
        roFactory = ::GetDataRo
    )
    operation(
        interfaceClass = GetPreview::class,
        protocolSchema = GetPreview.Schema,
        roFactory = ::GetPreviewRo
    )
    // ⚠⚠ Without this the dynamic-property template has no client binding and every
    // select projecting File.Thumbnail/ThumbnailLarge fails as an opaque
    // `error/internal` — sync-device-catch-up.md §11.3, trap 1.
    operation(
        interfaceClass = GetStoredThumbnail::class,
        protocolSchema = GetStoredThumbnail.Schema,
        roFactory = ::GetStoredThumbnailRo
    )
    operation(
        interfaceClass = FileCategoryOf::class,
        protocolSchema = FileCategoryOf.Schema,
        roFactory = ::FileCategoryOfRo
    )

    type(
        interfaceClass = FileService::class,
        dataSchema = FileService.Schema,
        roFactory = ::FileServiceRo
    )

    type(
        interfaceClass = FileSystem::class,
        dataSchema = FileSystem.Schema,
        roFactory = ::FileSystemRo
    )

    // Mode-A link sharing (docs/file-sharing.md).
    // Typed ROs for the `Share` entity (so `FileService.shares.search { ... }`
    // resolves predicate receivers) + the `ShareLinkResult` typed op return.
    type(
        interfaceClass = Share::class,
        dataSchema = Share.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<Share>(chain, ctx, Share.Schema), Share {
                override val schema: CompositeSchema get() = Share.Schema
            }
        }
    )
    type(
        interfaceClass = ShareLinkResult::class,
        dataSchema = ShareLinkResult.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, ShareLinkResult.Schema), ShareLinkResult {
                override val schema: CompositeSchema get() = ShareLinkResult.Schema
            }
        }
    )
    operation(
        interfaceClass = ShareLink::class,
        protocolSchema = ShareLink.Schema,
        roFactory = ::ShareLinkRo
    )
    operation(
        interfaceClass = RevokeShareLink::class,
        protocolSchema = RevokeShareLink.Schema,
        roFactory = ::RevokeShareLinkRo
    )
}
