package org.tenshu.file.common

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.StringDataIdDto
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.relation.ServiceDataRelation
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.constant
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.entityRelation
import org.tenshu.core.common.module.relationMembership
import org.tenshu.core.common.module.fulfilValueRequirement
import org.tenshu.core.common.module.referenceProperty
import org.tenshu.core.common.module.serviceDataRelation
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.serviceRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.file.system.ReplaceSourceFileBg
import org.tenshu.core.file.system.UploadSourceFileBg
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.module.value
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.Category
import org.tenshu.core.file.Trashed
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.Data
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FileCategoryOf
import org.tenshu.core.file.FileDataIdProvider
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Preview
import org.tenshu.core.file.Size
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.FileService
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileModificationDataIdProvider
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.shared.SharedFileDataIdProvider
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.placement.PlacementDataIdProvider
import org.tenshu.core.file.notifications.FileAddedBurst
import org.tenshu.core.file.notifications.FileAddedBurstDataIdProvider
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.ContentPinnedError
import org.tenshu.core.file.system.StillSyncingError
import org.tenshu.core.file.system.MoveNeedsLocalBytesError
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.HasLocalData
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.core.file.system.BackfillThumbnailClasses
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CameraMake
import org.tenshu.core.file.CameraModel
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.system.ListPlacements
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.file.system.ListSharedFolder
import org.tenshu.core.file.system.MountSharedRealm
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.file.system.ReadSharedFile
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.ReplaceFileBg
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.system.UploadFileBg
import org.tenshu.core.schema.propertyRelation
import org.tenshu.file.common.impl.file.FileDataIdProviderImpl
import org.tenshu.file.common.impl.file.CapturedPhotoDto
import org.tenshu.file.common.impl.file.FileDto
import org.tenshu.file.common.impl.modification.CreatedFileModificationDto
import org.tenshu.file.common.impl.modification.FileModificationDto
import org.tenshu.file.common.impl.modification.ContentChangedFileModificationDto
import org.tenshu.file.common.impl.modification.RenamedFileModificationDto
import org.tenshu.file.common.impl.modification.MovedFileModificationDto
import org.tenshu.file.common.impl.modification.FileModificationDataIdProviderImpl
import org.tenshu.file.common.impl.file.FilePathDto
import org.tenshu.file.common.impl.file.error.AlreadyExistsErrorDto
import org.tenshu.file.common.impl.file.error.ContentPinnedErrorDto
import org.tenshu.file.common.impl.file.error.StillSyncingErrorDto
import org.tenshu.file.common.impl.file.error.MoveNeedsLocalBytesErrorDto
import org.tenshu.file.common.impl.file.folder.FolderDto
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.ShareDataIdProvider
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.file.sync.FileSyncSettingDataIdProvider
import org.tenshu.core.file.sync.PathSyncOverrideEntry
import org.tenshu.core.file.sync.PathSyncOverrideEntryDataIdProvider
import org.tenshu.core.file.sync.PathSyncOverrideSet
import org.tenshu.core.file.sync.PathSyncOverrideSetDataIdProvider
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.file.common.impl.sync.FileSyncSettingDto
import org.tenshu.file.common.impl.sync.FileSyncSettingRo
import org.tenshu.file.common.impl.sync.FileSyncSettingDataIdProviderImpl
import org.tenshu.file.common.impl.sync.PathSyncOverrideEntryDataIdProviderImpl
import org.tenshu.file.common.impl.sync.PathSyncOverrideEntryDto
import org.tenshu.file.common.impl.sync.PathSyncOverrideSetDataIdProviderImpl
import org.tenshu.file.common.impl.sync.PathSyncOverrideSetDto
import org.tenshu.preference.PreferenceService
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.file.common.impl.notifications.FileAddedBurstDataIdProviderImpl
import org.tenshu.file.common.impl.notifications.FileAddedBurstDto
import org.tenshu.file.common.impl.notifications.FileAddedNotificationDto
import org.tenshu.file.common.impl.notifications.FileDeletedNotificationDto
import org.tenshu.file.common.impl.notifications.FolderAddedNotificationDto
import org.tenshu.file.common.impl.notifications.FolderDeletedNotificationDto
import org.tenshu.file.common.impl.notifications.ChangeLostNotificationDto
import org.tenshu.file.common.impl.shared.SharedFileDataIdProviderImpl
import org.tenshu.file.common.impl.shared.SharedFileDto
import org.tenshu.file.common.impl.placement.PlacementDataIdProviderImpl
import org.tenshu.file.common.impl.placement.PlacementDto
import org.tenshu.file.common.impl.share.ShareDataIdProviderImpl
import org.tenshu.file.common.impl.share.ShareDto
import org.tenshu.file.common.impl.share.ShareLinkResultDto
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.typeOf
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileCount
import org.tenshu.core.file.Name
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.ViewerPreview
import org.tenshu.core.file.shared.Children

val fileServiceCommonModule = tModule {
    include(notificationServiceCommonModule)

    composite(
        interfaceClass = FileAddedNotification::class,
        dataSchema = FileAddedNotification.Schema,
        dtoFactory = ::FileAddedNotificationDto
    )

    // Local-only producer coalescing pointer (never synced) — needs a schema
    // registration so the proxy's object store can (de)serialise it. See
    // FileAddedBurst's KDoc.
    composite(
        interfaceClass = FileAddedBurst::class,
        dataSchema = FileAddedBurst.Schema,
        dtoFactory = ::FileAddedBurstDto
    )
    trait(
        dataSchema = FileAddedBurst.Schema,
        traitSchema = FileAddedBurstDataIdProvider.Schema,
        traitFactory = { context, schema ->
            FileAddedBurstDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = FileDeletedNotification::class,
        dataSchema = FileDeletedNotification.Schema,
        dtoFactory = ::FileDeletedNotificationDto
    )

    composite(
        interfaceClass = FolderAddedNotification::class,
        dataSchema = FolderAddedNotification.Schema,
        dtoFactory = ::FolderAddedNotificationDto
    )

    composite(
        interfaceClass = FolderDeletedNotification::class,
        dataSchema = FolderDeletedNotification.Schema,
        dtoFactory = ::FolderDeletedNotificationDto
    )

    // A change lost to a freed, unrepairable file (`content-plane-cr2-b4-spec.md` §2.4).
    composite(
        interfaceClass = ChangeLostNotification::class,
        dataSchema = ChangeLostNotification.Schema,
        dtoFactory = ::ChangeLostNotificationDto
    )

    composite(
        interfaceClass = File::class,
        dataSchema = File.Schema,
        dtoFactory = ::FileDto
    )
    composite(
        interfaceClass = Folder::class,
        dataSchema = Folder.Schema,
        dtoFactory = ::FolderDto
    )
    // The photo read's return shape (`timeline-photos.md`) — a projection of an image
    // `File`, computed per call and stored nowhere. Registered as an ordinary composite because it
    // crosses the wire; it carries no DataIdProvider and no Syncable trait, so nothing can persist
    // one by accident.
    composite(
        interfaceClass = CapturedPhoto::class,
        dataSchema = CapturedPhoto.Schema,
        dtoFactory = ::CapturedPhotoDto
    )
    composite(
        interfaceClass = AlreadyExistsError::class,
        dataSchema = AlreadyExistsError.Schema,
        dtoFactory = ::AlreadyExistsErrorDto
    )

    // An unsent upload's bytes are the only copy, so evicting them is refused with an
    // answer rather than no-opped.
    composite(
        interfaceClass = ContentPinnedError::class,
        dataSchema = ContentPinnedError.Schema,
        dtoFactory = ::ContentPinnedErrorDto
    )

    // A provisional slice-snapshot row cannot be moved until the log reaches it
    // (`content-plane-cr2-b5-spec.md` §4).
    composite(
        interfaceClass = StillSyncingError::class,
        dataSchema = StillSyncingError.Schema,
        dtoFactory = ::StillSyncingErrorDto
    )

    // An offline move of content this device does not hold is refused
    // (`content-plane-cr2-b6-spec.md` D3).
    composite(
        interfaceClass = MoveNeedsLocalBytesError::class,
        dataSchema = MoveNeedsLocalBytesError.Schema,
        dtoFactory = ::MoveNeedsLocalBytesErrorDto
    )

    // File modification history (docs/proposals/file-service-history.md).
    // Abstract parent (never instantiated); the four subtypes carry DTOs. The DataIdProvider + Syncable trait factories sit on
    // the parent and are inherited by the subtypes (the File -> Folder pattern).
    // Register the abstract parent WITH its interfaceClass (not schema-only) so
    // `FileModification::class` maps to a type id — required for the chain DSL
    // `modifications.filter<FileModification> { … }` per-file history accessor.
    // Mirrors how NotificationServiceCommonModule registers the Notification
    // parent with NotificationDto.
    composite(
        interfaceClass = FileModification::class,
        dataSchema = FileModification.Schema,
        dtoFactory = ::FileModificationDto
    )
    composite(
        interfaceClass = CreatedFileModification::class,
        dataSchema = CreatedFileModification.Schema,
        dtoFactory = ::CreatedFileModificationDto
    )
    composite(
        interfaceClass = ContentChangedFileModification::class,
        dataSchema = ContentChangedFileModification.Schema,
        dtoFactory = ::ContentChangedFileModificationDto
    )
    composite(
        interfaceClass = RenamedFileModification::class,
        dataSchema = RenamedFileModification.Schema,
        dtoFactory = ::RenamedFileModificationDto
    )
    composite(
        interfaceClass = MovedFileModification::class,
        dataSchema = MovedFileModification.Schema,
        dtoFactory = ::MovedFileModificationDto
    )
    trait(
        dataSchema = FileModification.Schema,
        traitSchema = FileModificationDataIdProvider.Schema,
        traitFactory = { context, schema ->
            FileModificationDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    trait(
        dataSchema = FileModification.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    value(
        interfaceClass = FilePath::class,
        dataSchema = FilePath.Schema,
        dtoFactory = ::FilePathDto
    )

    operation(CreateFolder.Schema)
    operation(DeleteFile.Schema)
    operation(EvictFileBytes.Schema)
    operation(HasLocalData.Schema)
    operation(OfflineKeeper.Schema)
    // The headless thumbnail-class backfill (`drive-synced-thumbnails.md` §15.3).
    operation(BackfillThumbnailClasses.Schema)
    operation(UploadFile.Schema)
    operation(ReplaceFile.Schema)
    // Background (cancellable Layer-2) folder-upload variants — same impls,
    // BackgroundOperation trait. See docs/proposals/drive-folder-upload.md.
    operation(UploadFileBg.Schema)
    operation(ReplaceFileBg.Schema)
    composite(AlreadyExistsError.Schema)
    operation(GetData.Schema)
    operation(GetPreview.Schema)
    operation(GetStoredThumbnail.Schema)
    operation(FileCategoryOf.Schema)

    // Mode-A link sharing (docs/file-sharing.md).
    // `Share` is `Syncable(Eager)` — `My shares` list converges across realm
    // devices through the existing block log; `ShareLinkResult` is the typed
    // return of the `ShareLink` op (the caller — Drive UI — assembles the
    // copyable URL from `shareId` + `urlFragment`).
    composite(
        interfaceClass = Share::class,
        dataSchema = Share.Schema,
        dtoFactory = ::ShareDto
    )
    composite(
        interfaceClass = ShareLinkResult::class,
        dataSchema = ShareLinkResult.Schema,
        dtoFactory = ::ShareLinkResultDto
    )
    trait(
        dataSchema = Share.Schema,
        traitSchema = ShareDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ShareDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // Typed per-device sync-settings projection (drive-offline-online.md
    // §"Follow-up — a first-class serviceDataRelation"). A derived, non-`Syncable`
    // entity: the `DeviceSetting` rows are the synced originals, and the
    // proxy-side `FileSyncSettingsRelationMaintainer` re-derives one of these per
    // device on each file-sync setting save. Keyed by `deviceId`.
    composite(
        interfaceClass = FileSyncSetting::class,
        dataSchema = FileSyncSetting.Schema,
        dtoFactory = ::FileSyncSettingDto,
        // The chain reads it via `search<FileSyncSetting> { deviceId equalTo … }`, so
        // the element type needs a typed RO for the predicate receiver (mirrors
        // DeviceSetting/RealmSetting). Without it the read fails with "No request
        // object factory registered for type".
        roFactory = { chain, ctx ->
            FileSyncSettingRo(
                requestChain = chain,
                context = ctx,
                schema = FileSyncSetting.Schema,
            )
        }
    )
    trait(
        dataSchema = FileSyncSetting.Schema,
        traitSchema = FileSyncSettingDataIdProvider.Schema,
        traitFactory = { context, schema ->
            FileSyncSettingDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    trait(
        dataSchema = Share.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )
    operation(ShareLink.Schema)
    operation(RevokeShareLink.Schema)

    // Share a personal folder into an already-bootstrapped shared realm by minting
    // placements for its subtree (docs/proposals/drive-shared-realm-file-model.md
    // §6). Pure file-service: realm bootstrap is the separate
    // SyncService.createSharedRealm() op; this only adds Placement records, leaving
    // the personal File untouched.
    operation(ShareFolderIntoRealm.Schema)

    // Shared-realm read path (drive-shared-realm-file-model.md §6): browse a
    // realm's SharedFile tree by (realmId, parentFileId), and read a shared file's
    // bytes by (realmId, fileId) — dedicated ops because RequestChain can't root a
    // chain at a runtime realm id.
    operation(ListSharedFolder.Schema)
    operation(ReadSharedFile.Schema)

    // Mint on view (`drive-xxlarge-thumbnail-rung.md` §4.6): the client asks, the proxy pulls
    // or renders, and every gate (declined, quota, metered) is enforced there rather than in the UI.
    operation(EnsureThumbnailClass.Schema)

    // The connector op (drive-shared-realm-file-model.md §3 D3 / §6):
    // mount a shared realm into the member's personal FS by creating a `Placement`.
    operation(MountSharedRealm.Schema)
    // List the member's mounts (§6) — the flat PLACEMENTS collection the generic
    // relation maintainer derives, powering the Drive Shared / Shared-with-me
    // sections.
    operation(ListPlacements.Schema)

    // Every image captured inside a time window (`timeline-photos.md`) — the scan runs in the
    // proxy so only the matches cross IPC, because the chain DSL cannot express a range at all.
    operation(PhotosCapturedBetween.Schema)

    // A file inside a shared realm (docs/proposals/drive-shared-realm-file-model.md,
    // additive model). `SharedFile` is the *content* side — it locates a file in
    // the shared realm's own internal tree by a stable, location-independent
    // fileId, additive to the unchanged path-keyed personal `File`. The *connector*
    // (a per-member mount of the realm into a personal FS) is the separate
    // `Placement` type (§6). `Syncable(Eager)`
    // so the realm's file set converges across its members via the block log; keyed
    // by the derived `compositeKey` ("$realmId::$fileId") through
    // `SharedFileDataIdProvider`. Its per-parent children tree is derived by its
    // relation membership (`FileRelationMembership.kt`).
    composite(
        interfaceClass = SharedFile::class,
        dataSchema = SharedFile.Schema,
        dtoFactory = ::SharedFileDto
    )
    trait(
        dataSchema = SharedFile.Schema,
        traitSchema = SharedFileDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SharedFileDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    trait(
        dataSchema = SharedFile.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    // The *connector* (drive-shared-realm-file-model.md §3 D3): a per-member mount
    // of a whole shared realm into that member's personal FS. References only a realm
    // id (content-type-agnostic by construction), keyed by `sharedRealmId` through
    // `PlacementDataIdProvider`. `Syncable(Eager)` and **unprefixed** so it routes to
    // the **personal** realm — it syncs across the member's own devices but is private
    // to them (not shared content). Distinct from `SharedFile` (the content side).
    composite(
        interfaceClass = Placement::class,
        dataSchema = Placement.Schema,
        dtoFactory = ::PlacementDto
    )
    trait(
        dataSchema = Placement.Schema,
        traitSchema = PlacementDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PlacementDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    trait(
        dataSchema = Placement.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    // The by-reference upload/replace ops. ⚠ Registered HERE, not in a module of their own, and
    // that placement is the whole point: `SchemaShapeBaselineEnforcementTest` builds its registry
    // from this module, so ops registered elsewhere are invisible to it — their positional layout
    // pinned by nothing — and the registry attributes them to NO SERVICE, which would make the next
    // reshape of an upload param demand a store-wiping `TENSHU_STORAGE_FORMAT_VERSION` bump instead
    // of a file-service one. See `docs/tech-debt.md` and `drive-upload-source-read.md` §5 Q2.
    // ⚠ A platform with no implementation simply never calls these; the schemas are inert until a
    // factory supplies one.
    operation(UploadSourceFile.Schema)
    operation(ReplaceSourceFile.Schema)
    operation(UploadSourceFileBg.Schema)
    operation(ReplaceSourceFileBg.Schema)

    service(FileService.Schema)
    service(FileSystem.Schema)

    serviceDataRelation(
        fromId = FileService.Relations.DEFAULT_FILE_SYSTEM,
        targetId = FileSystem.Relations.ROOT,
        relation = ServiceDataRelation.create(
            from = FileService.Relations.DEFAULT_FILE_SYSTEM,
            to = FileSystem.Relations.ROOT,
            type = Folder.Schema.toType(),
            dataId = StringDataIdDto(
                type = File.ID,
                keyValue = "/"
            )
        )
    )

    serviceRelation(
        fromId = FileService.ID,
        targetId = FileService.Relations.DEFAULT_FILE_SYSTEM
    )

    // First-class per-device sync-settings relation, hung off **PreferenceService**
    // (cross-config — `:preference-service` is unchanged; the registry resolves it).
    // `preferenceService.fileSyncSettings` reads as a `TList<FileSyncSetting>`,
    // populated by the proxy-side `FileSyncSettingsRelationMaintainer` from the
    // synced `file.sync.*` `DeviceSetting` rows. Same shape as MODIFICATIONS/SHARES,
    // but the projection entity is derived (not `Syncable`) — see
    // `drive-offline-online.md` §"Follow-up — a first-class serviceDataRelation".
    serviceDataRelation(
        fromId = PreferenceService.ID,
        targetId = FileSyncSetting.RELATION,
        relation = ServiceDataRelation.create(
            from = PreferenceService.ID,
            to = FileSyncSetting.RELATION,
            type = TList.typeOf(FileSyncSetting.Schema),
        )
    )

    constant(
        interfaceClass = FileCategory::class,
        dataSchema = FileCategory.Schema,
        dtoFactory = { PropertyTypeReferenceDto(FileCategory.Schema.id.get()) }
    )
    constant(
        interfaceClass = FileCategory.Image::class,
        dataSchema = FileCategory.Image.schema,
        dtoFactory = { FileCategory.Image }
    )
    constant(
        interfaceClass = FileCategory.Audio::class,
        dataSchema = FileCategory.Audio.schema,
        dtoFactory = { FileCategory.Audio }
    )
    constant(
        interfaceClass = FileCategory.Video::class,
        dataSchema = FileCategory.Video.schema,
        dtoFactory = { FileCategory.Video }
    )
    constant(
        interfaceClass = FileCategory.Document::class,
        dataSchema = FileCategory.Document.schema,
        dtoFactory = { FileCategory.Document }
    )
    constant(
        interfaceClass = FileCategory.Archive::class,
        dataSchema = FileCategory.Archive.schema,
        dtoFactory = { FileCategory.Archive }
    )
    constant(
        interfaceClass = FileCategory.Other::class,
        dataSchema = FileCategory.Other.schema,
        dtoFactory = { FileCategory.Other }
    )

    // Per-device sync policy as a first-class constant (drive-offline-online.md §4).
    // Parent + one entry per object, same shape as FileCategory. The policy rides
    // the synced `file.sync.defaultPolicy` preference value (a genericData slot) and
    // the `PathSyncOverrideEntry.policy` field directly, with no codec.
    constant(
        interfaceClass = SyncPolicy::class,
        dataSchema = SyncPolicy.Schema,
        dtoFactory = { PropertyTypeReferenceDto(SyncPolicy.Schema.id.get()) }
    )
    constant(
        interfaceClass = SyncPolicy.KEEP_OFFLINE::class,
        dataSchema = SyncPolicy.KEEP_OFFLINE.schema,
        dtoFactory = { SyncPolicy.KEEP_OFFLINE }
    )
    constant(
        interfaceClass = SyncPolicy.ONLINE_ONLY::class,
        dataSchema = SyncPolicy.ONLINE_ONLY.schema,
        dtoFactory = { SyncPolicy.ONLINE_ONLY }
    )

    // Persisted override composite (drive-offline-online.md §4.10) — a list element,
    // so it carries a DataIdProvider (keyed by pathPrefix). Its `policy` is a
    // SyncPolicy constant field.
    composite(
        interfaceClass = PathSyncOverrideEntry::class,
        dataSchema = PathSyncOverrideEntry.Schema,
        dtoFactory = ::PathSyncOverrideEntryDto
    )
    trait(
        dataSchema = PathSyncOverrideEntry.Schema,
        traitSchema = PathSyncOverrideEntryDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PathSyncOverrideEntryDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    // Wrapper carrying the override list — the storable shape of the
    // `file.sync.overrides` preference value (genericData can't hold a bare array).
    // Content-addressed via a DataIdProvider so its list field is addressable and
    // identical sets across devices fold to one immutable entry.
    composite(
        interfaceClass = PathSyncOverrideSet::class,
        dataSchema = PathSyncOverrideSet.Schema,
        dtoFactory = ::PathSyncOverrideSetDto
    )
    trait(
        dataSchema = PathSyncOverrideSet.Schema,
        traitSchema = PathSyncOverrideSetDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PathSyncOverrideSetDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    registerPropertyRelation(fromId = Folder.ID, schema = propertyRelation(Folder.Fields.FILES))

    // File properties as Property handles — see
    // docs/adr/0013-property-authoring-unification.md +
    // file-service-history.md §3.3. Size / LastModifiedAt are op-written stored
    // scalars (carry a value factory); Category is a constant/singleton (no
    // factory); Data / Preview are dynamic chains. All inherited by Folder via
    // the entity's properties map.
    valueProperty(fromId = File.ID, property = File.Size) { TIntegerDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.LastModifiedAt) { UnixInstantDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.CreatedAt) { UnixInstantDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.ContentModifiedAt) { UnixInstantDto(it as Long) }
    // Content addressing: stable random content id + whole-file content hash,
    // op-written stored TString scalars eagerly synced like Size (file-content-addressing.md §3.1).
    valueProperty(fromId = File.ID, property = File.ContentId) { TStringDto(it as String) }
    // The slot every content id of a File derives from (`content-plane-cr2-quorumless.md` §10.11):
    // `File.Data` is this value, each thumbnail rung a hash of it (`ContentDerivation` on the
    // property). Resolved generically by `contentEdgesOf`, so the sync layer never names `File`.
    fulfilValueRequirement(
        fromId = File.ID,
        requirementId = ContentReferencing.CONTENT_ID,
        property = File.ContentId,
    )
    valueProperty(fromId = File.ID, property = File.ContentHash) { TStringDto(it as String) }
    // Where the photograph was taken, from its own EXIF. Two scalars, written together at
    // upload or not at all — most files have neither.
    valueProperty(fromId = File.ID, property = File.CaptureLatE7) { TIntegerDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.CaptureLngE7) { TIntegerDto(it as Long) }
    // What the file says about itself — pixel size, EXIF orientation, camera and, for a
    // video, its length. Each is absent unless the container carried it; see [File.PixelWidth] for
    // why the stored size is not the displayed one.
    valueProperty(fromId = File.ID, property = File.PixelWidth) { TIntegerDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.PixelHeight) { TIntegerDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.Orientation) { TIntegerDto(it as Long) }
    valueProperty(fromId = File.ID, property = File.CameraMake) { TStringDto(it as String) }
    valueProperty(fromId = File.ID, property = File.CameraModel) { TStringDto(it as String) }
    valueProperty(fromId = File.ID, property = File.DurationMs) { TIntegerDto(it as Long) }
    // ⚠ A `dynamicProperty`, not a `valueProperty` (`photos-app.md`): nothing writes a stored
    // category, so it is derived from the name on read — see [File.Category]. Resolved by
    // `FileCategoryOf`.
    dynamicProperty(fromId = File.ID, property = File.Category)
    // Derived from the path, so the Photos feed can exclude the trash inside the chain (the
    // DSL has no negation, so `path` cannot express "not under /Trash").
    dynamicProperty(fromId = File.ID, property = File.Trashed)
    // ⚠⚠ Every thumbnail class is a `dynamicProperty(Syncable(OnDemand))` resolved by
    // GetStoredThumbnail from the content plane, never an eagerly-synced `valueProperty`: as
    // row bytes they are ~97 % of what a File row occupies (~18.4 KB against ~336 B of
    // metadata), all of it riding the eager entity block to every paired device before that
    // device is caught up (sync-device-catch-up.md §11.3, drive-synced-thumbnails.md §10.1).
    dynamicProperty(fromId = File.ID, property = File.Thumbnail)
    dynamicProperty(fromId = File.ID, property = File.ThumbnailLarge)
    dynamicProperty(fromId = File.ID, property = File.ThumbnailXLarge)
    dynamicProperty(fromId = File.ID, property = File.ThumbnailXXLarge)
    dynamicProperty(fromId = File.ID, property = File.ThumbnailMotion)
    dynamicProperty(fromId = File.ID, property = File.Data)
    dynamicProperty(fromId = File.ID, property = File.Preview)
    // ⚠⚠ D-17's viewer read (`drive-xxlarge-thumbnail-rung.md`). Same resolver as
    // `File.Preview`, one flag apart — registered separately because a dynamic property's
    // parameter list is positional schema, so the flag could not ride the existing one.
    dynamicProperty(fromId = File.ID, property = File.ViewerPreview)
    // Derived last-path-segment name (chain-computed from File.path, never stored).
    // Non-deferred + synchronous, so it also resolves inside predicates —
    // `filter<File> { name contains q }`.
    dynamicProperty(fromId = File.ID, property = File.Name)
    // Cheap child count (member-hash size, no materialisation) for the
    // "N files" counter — declared on Folder, the only type carrying `files`.
    dynamicProperty(fromId = Folder.ID, property = Folder.FileCount)

    // File.CreatedAt fulfils RealmData.CREATED_AT — the explicit binding the Data section
    // resolves to sort Files newest-first (File has two UnixInstant properties, CreatedAt and
    // LastModifiedAt, so it can't be inferred). docs/proposals/core-realm-data.md §8.
    fulfilValueRequirement(
        fromId = File.ID,
        requirementId = RealmData.CREATED_AT,
        property = File.CreatedAt,
    )

    // FileModification.deviceId → Device (reference value property). Resolution
    // dereferences the live Device by key at the proxy; the wire selector is the uniform
    // StaticPropertyRelation. See docs/proposals/core-reference-property.md.
    referenceProperty(
        fromId = FileModification.ID,
        property = FileModification.Device,
        keyField = FileModification.Field.DEVICE_ID,
    )

    // File → its FileModification history (entity relation). Resolution reads the
    // file's per-entity array (EntityPropertyDataId) at the proxy; the wire selector
    // is the uniform StaticPropertyRelation.
    // See docs/proposals/core-nested-projection.md §3.3.
    entityRelation(
        fromId = File.ID,
        property = File.FileHistory,
    )

    // SharedFile → its child SharedFiles (entity relation): the navigable shared-realm
    // tree edge (drive-shared-realm-file-model.md §6). Same shape as
    // File.FileHistory — a per-parent array (EntityPropertyDataId under
    // SharedFile.CHILDREN_RELATION_ID); projected via
    // `relations(SharedFile.Children) { … }`.
    entityRelation(
        fromId = SharedFile.ID,
        property = SharedFile.Children,
    )

    // The *write* half of the three entity-scoped relations above (+ Folder.FILES),
    // declared here because no schema can carry a per-entity array's key or its
    // parent derivation (`core-relation-system.md` §9.2.1 / R2). The specs and
    // their reasoning live in FileRelationMembership.kt.
    relationMembership(FolderFilesMembership)
    relationMembership(FileHistoryMembership)
    relationMembership(SharedFileChildrenMembership)

    trait(
        dataSchema = File.Schema,
        traitSchema = FileDataIdProvider.Schema,
        traitFactory = { context, schema ->
            FileDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = File.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )
}
