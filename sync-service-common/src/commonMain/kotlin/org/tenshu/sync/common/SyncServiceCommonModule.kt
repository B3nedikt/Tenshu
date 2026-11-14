package org.tenshu.sync.common

import org.tenshu.core.common.impl.TConstantRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.sync.PairingFailure
import org.tenshu.core.Composite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.constant
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.serviceDataRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.module.type
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.relation.ServiceDataRelation
import org.tenshu.core.schema.typeOf
import org.tenshu.sync.ContentTransferStatus
import org.tenshu.sync.ContentUploadProgress
import org.tenshu.sync.ContentUploadProgressDataIdProvider
import org.tenshu.sync.ContentTransferStatusDataIdProvider
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.RuntimeIdentityDataIdProvider
import org.tenshu.sync.SchemaFenceStatus
import org.tenshu.sync.SchemaFenceStatusDataIdProvider
import org.tenshu.sync.SyncCatchUpHeartbeat
import org.tenshu.sync.SyncCatchUpHeartbeatDataIdProvider
import org.tenshu.sync.SyncCatchUpStatus
import org.tenshu.sync.SyncCatchUpStatusDataIdProvider
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncHealthStatusDataIdProvider
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.AwaitPairing
import org.tenshu.sync.CancelPairing
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.CreateSharedRealm
import org.tenshu.sync.GrantSharedRealmMembership
import org.tenshu.sync.AcceptSharedRealmGrant
import org.tenshu.sync.DeclinePendingGrant
import org.tenshu.sync.PollPendingGrants
import org.tenshu.sync.RemoveSharedRealmMember
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.FileChunkRef
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.GetRecoveryPhrase
import org.tenshu.sync.GetSyncStatus
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.GetUnlockStatus
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.OfferHubPairing
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.RespondToHubPairing
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.CancelScheduled
import org.tenshu.sync.CompactSchema
import org.tenshu.sync.DrainSync
import org.tenshu.sync.SyncNow
import org.tenshu.sync.CloseContentStream
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ContentStreamHandle
import org.tenshu.sync.OpenContentStream
import org.tenshu.sync.ReadContentRange
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.ReadmitDevice
import org.tenshu.sync.ReregisterDevice
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleService
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.SetUpLocalUnlock
import org.tenshu.sync.SetUpUnlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.StartPairing
import org.tenshu.sync.StopPairing
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.PairingDeviceRemovedError
import org.tenshu.sync.PairingApproverRevokedError
import org.tenshu.sync.PairingThisDeviceRemovedError
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.RecoveryPhraseUnreadableError
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.UnlockLocal
import org.tenshu.sync.UnlockStatus

val syncServiceCommonModule = tModule {

    service(
        dataSchema = SyncService.Schema,
    )

    // Sync-health signal (drive-connectivity-service.md): the single-row,
    // non-Syncable SyncHealthStatus relation the proxy derives locally and Drive
    // observes over IPC. Composite + identity trait + the service relation so it
    // crosses the proxy↔client wire.
    composite(
        interfaceClass = SyncHealthStatus::class,
        dataSchema = SyncHealthStatus.Schema,
        dtoFactory = ::SyncHealthStatusDto,
        roFactory = { chain, ctx ->
            SyncHealthStatusRo(
                requestChain = chain,
                context = ctx,
                schema = SyncHealthStatus.Schema,
            )
        },
    )
    trait(
        dataSchema = SyncHealthStatus.Schema,
        traitSchema = SyncHealthStatusDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SyncHealthStatusDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Schema pull-fence signal (core-schema-evolution.md §7.3): the
    // single-row, non-Syncable SchemaFenceStatus the proxy derives locally when the
    // realm epoch runs ahead of this device. Registered exactly like
    // SyncHealthStatus above — composite + Ro (the client-side chain needs it, §6.10)
    // + identity trait + the service relation below.
    composite(
        interfaceClass = SchemaFenceStatus::class,
        dataSchema = SchemaFenceStatus.Schema,
        dtoFactory = ::SchemaFenceStatusDto,
        roFactory = { chain, ctx ->
            SchemaFenceStatusRo(
                requestChain = chain,
                context = ctx,
                schema = SchemaFenceStatus.Schema,
            )
        },
    )
    trait(
        dataSchema = SchemaFenceStatus.Schema,
        traitSchema = SchemaFenceStatusDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SchemaFenceStatusDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Runtime host identity (core-schema-skew-surface.md SK-2): the single-row,
    // non-Syncable RuntimeIdentity the proxy publishes once at init so a client can
    // name the right binary to update. Registered exactly like SchemaFenceStatus above.
    //
    // ⚠⚠ The composite + Ro registration is what makes the row READABLE, and omitting it
    // fails at dispatch, never at compile: the proxy would publish, the relation would
    // exist, and every client select projecting it would die as an opaque error/internal
    // (§6.10). The schema-shape baseline does not catch it either: it regenerates to NO
    // CHANGE, because an unregistered composite is outside the walked graph.
    composite(
        interfaceClass = RuntimeIdentity::class,
        dataSchema = RuntimeIdentity.Schema,
        dtoFactory = ::RuntimeIdentityDto,
        roFactory = { chain, ctx ->
            RuntimeIdentityRo(
                requestChain = chain,
                context = ctx,
                schema = RuntimeIdentity.Schema,
            )
        },
    )
    trait(
        dataSchema = RuntimeIdentity.Schema,
        traitSchema = RuntimeIdentityDataIdProvider.Schema,
        traitFactory = { context, schema ->
            RuntimeIdentityDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.SYNC_HEALTH,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.SYNC_HEALTH,
            type = TList.typeOf(SyncHealthStatus.Schema),
        ),
    )
    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.SCHEMA_FENCE,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.SCHEMA_FENCE,
            type = TList.typeOf(SchemaFenceStatus.Schema),
        ),
    )

    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.RUNTIME_IDENTITY,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.RUNTIME_IDENTITY,
            type = TList.typeOf(RuntimeIdentity.Schema),
        ),
    )

    // Content-transfer signal (sync-metered-plane-split.md §8): the single-row,
    // non-Syncable ContentTransferStatus carrying how many files the metered content
    // gate is holding. Registered exactly like SchemaFenceStatus above.
    composite(
        interfaceClass = ContentTransferStatus::class,
        dataSchema = ContentTransferStatus.Schema,
        dtoFactory = ::ContentTransferStatusDto,
        roFactory = { chain, ctx ->
            ContentTransferStatusRo(
                requestChain = chain,
                context = ctx,
                schema = ContentTransferStatus.Schema,
            )
        },
    )
    trait(
        dataSchema = ContentTransferStatus.Schema,
        traitSchema = ContentTransferStatusDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ContentTransferStatusDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )
    // Upload-progress signal (drive-upload-progress.md P2): a SEPARATE single-row composite
    // rather than two more fields on ContentTransferStatus, which is positional and crosses the
    // Drive<->Control IPC boundary. See ContentUploadProgress for why a service bump was the wrong
    // alternative.
    composite(
        interfaceClass = ContentUploadProgress::class,
        dataSchema = ContentUploadProgress.Schema,
        dtoFactory = ::ContentUploadProgressDto,
        roFactory = { chain, ctx ->
            ContentUploadProgressRo(
                requestChain = chain,
                context = ctx,
                schema = ContentUploadProgress.Schema,
            )
        },
    )
    trait(
        dataSchema = ContentUploadProgress.Schema,
        traitSchema = ContentUploadProgressDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ContentUploadProgressDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )
    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.CONTENT_UPLOAD_PROGRESS,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.CONTENT_UPLOAD_PROGRESS,
            type = TList.typeOf(ContentUploadProgress.Schema),
        ),
    )
    // Pull activity (ios-sync-activity-indicator.md §3.1): one row PER WRITER, keyed by the host
    // app id, so several runtimes on one store never clear each other's row. Non-Syncable and
    // locally derived, like the three rows above.
    composite(
        interfaceClass = SyncCatchUpStatus::class,
        dataSchema = SyncCatchUpStatus.Schema,
        dtoFactory = ::SyncCatchUpStatusDto,
        roFactory = { chain, ctx ->
            SyncCatchUpStatusRo(
                requestChain = chain,
                context = ctx,
                schema = SyncCatchUpStatus.Schema,
            )
        },
    )
    trait(
        dataSchema = SyncCatchUpStatus.Schema,
        traitSchema = SyncCatchUpStatusDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SyncCatchUpStatusDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )
    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.CATCH_UP,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.CATCH_UP,
            type = TList.typeOf(SyncCatchUpStatus.Schema),
        ),
    )
    // The same pull, with a pulse (ios-sync-activity-indicator.md §12): one row per writer, rewritten
    // while it catches up, so a reader may count ANY writer's fresh row on a shared store. A new
    // non-Syncable type rather than a field of SyncCatchUpStatus: reshape-free, so no version bump.
    composite(
        interfaceClass = SyncCatchUpHeartbeat::class,
        dataSchema = SyncCatchUpHeartbeat.Schema,
        dtoFactory = ::SyncCatchUpHeartbeatDto,
        roFactory = { chain, ctx ->
            SyncCatchUpHeartbeatRo(
                requestChain = chain,
                context = ctx,
                schema = SyncCatchUpHeartbeat.Schema,
            )
        },
    )
    trait(
        dataSchema = SyncCatchUpHeartbeat.Schema,
        traitSchema = SyncCatchUpHeartbeatDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SyncCatchUpHeartbeatDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )
    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.CATCH_UP_HEARTBEAT,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.CATCH_UP_HEARTBEAT,
            type = TList.typeOf(SyncCatchUpHeartbeat.Schema),
        ),
    )
    serviceDataRelation(
        fromId = SyncService.ID,
        targetId = SyncService.Relations.CONTENT_TRANSFER,
        relation = ServiceDataRelation.create(
            from = SyncService.ID,
            to = SyncService.Relations.CONTENT_TRANSFER,
            type = TList.typeOf(ContentTransferStatus.Schema),
        ),
    )

    // Realm schema epoch (core-schema-evolution.md §7.1): the realm's
    // per-service "highest version any device has emitted under" row. Registered
    // as composite + Dto + Syncable trait + DataIdProvider (keyed on the constant
    // epochId). No service relation and no
    // client Ro: proxy-internal (written by `RealmSchemaEpochWriter`,
    // read back by DataId, merged by `RealmSchemaEpochMerger`), the same posture as
    // identity's `OwnedRealmMaster`.
    composite(
        interfaceClass = RealmSchemaEpoch::class,
        dataSchema = RealmSchemaEpoch.Schema,
        dtoFactory = ::RealmSchemaEpochDto,
    )
    trait(
        dataSchema = RealmSchemaEpoch.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = RealmSchemaEpoch.Schema,
        traitSchema = RealmSchemaEpoch.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Per-device recorded versions (core-schema-evolution.md §8.3): what ONE
    // device runs, so the readable floor can advance on the roster's MIN. ⚠ The
    // twin of RealmSchemaEpoch above and deliberately NOT part of it — the epoch
    // merges by MAX ("newest anyone wrote", for fencing) and this is read by MIN
    // ("oldest still in service", for cutting history off); a MAX-merged
    // aggregate cannot yield a MIN. Same proxy-internal posture: no service
    // relation, no client Ro.
    composite(
        interfaceClass = DeviceSchemaVersion::class,
        dataSchema = DeviceSchemaVersion.Schema,
        dtoFactory = ::DeviceSchemaVersionDto,
    )
    trait(
        dataSchema = DeviceSchemaVersion.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = DeviceSchemaVersion.Schema,
        traitSchema = DeviceSchemaVersion.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Compaction receipt (core-schema-evolution.md §8.3): the dedup signal —
    // "someone re-emitted service S at version N here". Keyed per service, so a
    // second device REPLACES rather than appends; nothing branches on who wrote
    // it. Same proxy-internal posture as the two rows above.
    composite(
        interfaceClass = CompactionReceipt::class,
        dataSchema = CompactionReceipt.Schema,
        dtoFactory = ::CompactionReceiptDto,
    )
    trait(
        dataSchema = CompactionReceipt.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = CompactionReceipt.Schema,
        traitSchema = CompactionReceipt.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Scheduling (core-background-processing.md §3.2(b)): ScheduleService +
    // its persisted ScheduleItem registry.
    service(
        dataSchema = ScheduleService.Schema,
    )

    operation(Schedule.Schema)
    operation(ListScheduled.Schema)
    operation(CancelScheduled.Schema)

    // The head-less sync-drain op (bgproc-scheduling-roles.md §6), which both
    // ScheduleDrainLoop (in-process) and WorkManager (app-closed Android)
    // dispatch by id. Registered so executeAsync/the BinaryParser resolve it.
    operation(DrainSync.Schema)
    // The compaction maintenance pass (core-schema-evolution.md §8.3), run by the scheduler.
    operation(CompactSchema.Schema)
    // C8 (`sync-device-catch-up.md` §5.7): the joiner-snapshot maintenance pass, on the same
    // idle+charging path. Registered so the schedule registry can address it by id.
    operation(org.tenshu.sync.WriteSliceSnapshots.Schema)
    operation(org.tenshu.sync.BackfillContentDeclarations.Schema)
    operation(SyncNow.Schema)

    composite(
        interfaceClass = ScheduleSpec::class,
        dataSchema = ScheduleSpec.Schema,
        dtoFactory = ::ScheduleSpecDto
    )

    composite(
        interfaceClass = ConstraintSpec::class,
        dataSchema = ConstraintSpec.Schema,
        dtoFactory = ::ConstraintSpecDto
    )

    composite(
        interfaceClass = RetryPolicy::class,
        dataSchema = RetryPolicy.Schema,
        dtoFactory = ::RetryPolicyDto
    )

    composite(
        interfaceClass = ScheduleItem::class,
        dataSchema = ScheduleItem.Schema,
        dtoFactory = ::ScheduleItemDto
    )

    trait(
        ScheduleItem.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        ScheduleItemDataIdProviderImpl(context)
    }

    operation(PushBlock.Schema)
    operation(GetChanges.Schema)
    // Streaming pull ops (drive-streaming-preview.md §3.5).
    operation(OpenContentStream.Schema)
    operation(ReadContentRange.Schema)
    operation(CloseContentStream.Schema)
    operation(PushTombstone.Schema)
    operation(GetTombstones.Schema)
    // The merged lane (sync-lane-cursor-split.md L3). Additive alongside the two it
    // supersedes: a relay or peer that never calls it is unaffected, which is what keeps
    // this free of a format bump.
    operation(GetSyncLog.Schema)
    // C2 backfill: a NEW op id, not a widened GetSyncLog — positional params cannot grow.
    operation(GetSyncLogBefore.Schema)
    operation(StartPairing.Schema)
    operation(StopPairing.Schema)
    operation(GetSyncStatus.Schema)
    operation(GetPendingPairingRequests.Schema)
    operation(GetRecoveryPhrase.Schema)
    operation(ApprovePairingRequest.Schema)
    operation(InitiatePairing.Schema)
    operation(GetPairingStatus.Schema)
    operation(AwaitPairing.Schema)
    operation(OfferPairing.Schema)
    operation(OfferHubPairing.Schema)
    operation(GetOfferStatus.Schema)
    operation(CancelPairingOffer.Schema)
    operation(RespondToPairingOffer.Schema)
    operation(RespondToHubPairing.Schema)
    operation(ApprovePairingOffer.Schema)
    operation(DenyPairingOffer.Schema)
    operation(CreateRealm.Schema)
    operation(ReregisterDevice.Schema)
    operation(ReadmitDevice.Schema)
    operation(CreateSharedRealm.Schema)
    operation(GrantSharedRealmMembership.Schema)
    operation(AcceptSharedRealmGrant.Schema)
    operation(RemoveSharedRealmMember.Schema)
    operation(PollPendingGrants.Schema)
    operation(DeclinePendingGrant.Schema)
    operation(RecoveryClaim.Schema)
    operation(GetUnlockStatus.Schema)
    operation(SetUpLocalUnlock.Schema)
    operation(SetUpUnlock.Schema)
    operation(UnlockLocal.Schema)
    operation(CancelPairing.Schema)

    composite(
        interfaceClass = SyncBlock::class,
        dataSchema = SyncBlock.Schema,
        dtoFactory = ::SyncBlockDto
    )

    // The client↔relay push bodies (`content-plane-cr2-b3-wire-spec.md` §3). ⚠ New types — no
    // version moves — and shared by the relay and the client, so neither side reshapes them alone.
    composite(
        interfaceClass = org.tenshu.sync.PushedEdge::class,
        dataSchema = org.tenshu.sync.PushedEdge.Schema,
        dtoFactory = ::PushedEdgeDto
    )
    composite(
        interfaceClass = org.tenshu.sync.ReviveEntry::class,
        dataSchema = org.tenshu.sync.ReviveEntry.Schema,
        dtoFactory = ::ReviveEntryDto
    )
    composite(
        interfaceClass = org.tenshu.sync.EdgedPush::class,
        dataSchema = org.tenshu.sync.EdgedPush.Schema,
        dtoFactory = ::EdgedPushDto
    )
    composite(
        interfaceClass = org.tenshu.sync.CascadedTombstonePush::class,
        dataSchema = org.tenshu.sync.CascadedTombstonePush.Schema,
        dtoFactory = ::CascadedTombstonePushDto
    )

    trait(
        SyncBlock.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        SyncBlockDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = org.tenshu.sync.DataBlockPayload::class,
        dataSchema = org.tenshu.sync.DataBlockPayload.Schema,
        dtoFactory = ::DataBlockPayloadDto
    )

    // File-content two-plane (docs/adr/0017-file-content-two-plane-channel.md): the manifest
    // is a distinct payload sibling of DataBlockPayload — receivers that
    // only know DataBlockPayload safely no-op on it (see
    // FileContentManifestPayload KDoc + SyncProcessor).
    composite(
        interfaceClass = FileChunkRef::class,
        dataSchema = FileChunkRef.Schema,
        dtoFactory = ::FileChunkRefDto
    )

    composite(
        interfaceClass = FileContentManifestPayload::class,
        dataSchema = FileContentManifestPayload.Schema,
        dtoFactory = ::FileContentManifestPayloadDto
    )

    // Task-scoped sync lane (core-background-processing.md §10): the
    // checkpoint container — the third payload sibling. One block, N
    // per-entity deltas each with its own HLC; see DeltaBatchPayload KDoc
    // for the receiver-safety / format-bump posture.
    composite(
        interfaceClass = org.tenshu.sync.SyncDelta::class,
        dataSchema = org.tenshu.sync.SyncDelta.Schema,
        dtoFactory = ::SyncDeltaDto
    )

    trait(
        org.tenshu.sync.SyncDelta.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        SyncDeltaDataIdProviderImpl(context)
    }

    // Task composite (core-task-composite.md §5): TaskService + the
    // register/advance/transition op set over the task-scoped sync lane.
    service(
        dataSchema = org.tenshu.sync.TaskService.Schema,
    )

    operation(org.tenshu.sync.RegisterTask.Schema)
    operation(org.tenshu.sync.RenewTaskLease.Schema)
    operation(org.tenshu.sync.TransitionTask.Schema)

    composite(
        interfaceClass = org.tenshu.core.TaskSpec::class,
        dataSchema = org.tenshu.core.TaskSpec.Schema,
        dtoFactory = ::TaskSpecDto
    )

    composite(
        interfaceClass = org.tenshu.sync.GenericTaskSpec::class,
        dataSchema = org.tenshu.sync.GenericTaskSpec.Schema,
        dtoFactory = ::GenericTaskSpecDto
    )

    composite(
        interfaceClass = org.tenshu.sync.BackgroundTask::class,
        dataSchema = org.tenshu.sync.BackgroundTask.Schema,
        dtoFactory = ::BackgroundTaskDto
    )

    composite(
        interfaceClass = org.tenshu.sync.DeltaBatchPayload::class,
        dataSchema = org.tenshu.sync.DeltaBatchPayload.Schema,
        dtoFactory = ::DeltaBatchPayloadDto
    )

    // Streaming (drive-streaming-preview.md §3.5): the OpenContentStream return value.
    composite(
        interfaceClass = ContentStreamHandle::class,
        dataSchema = ContentStreamHandle.Schema,
        dtoFactory = ::ContentStreamHandleDto
    )

    composite(
        interfaceClass = DeleteBlock::class,
        dataSchema = DeleteBlock.Schema,
        dtoFactory = ::DeleteBlockDto
    )

    trait(
        DeleteBlock.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        DeleteBlockDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = SignedHlcTip::class,
        dataSchema = SignedHlcTip.Schema,
        dtoFactory = ::SignedHlcTipDto
    )

    composite(
        interfaceClass = ChangesResponse::class,
        dataSchema = ChangesResponse.Schema,
        dtoFactory = ::ChangesResponseDto
    )

    composite(
        interfaceClass = TombstonesResponse::class,
        dataSchema = TombstonesResponse.Schema,
        dtoFactory = ::TombstonesResponseDto
    )

    composite(
        interfaceClass = SyncLogResponse::class,
        dataSchema = SyncLogResponse.Schema,
        dtoFactory = ::SyncLogResponseDto
    )

    // ⚠ Must be registered even while its only consumer is proxy-internal: an
    // unregistered error type fails to parse on a client, which would turn the very case it
    // names — "your relay is older than this client" — back into an opaque error/internal.
    composite(
        interfaceClass = RelayRouteUnsupportedError::class,
        dataSchema = RelayRouteUnsupportedError.Schema,
        dtoFactory = ::RelayRouteUnsupportedErrorDto
    )

    // ⚠ Registered for the same reason `RelayRouteUnsupportedError` above is: an unregistered
    // error type fails to parse on a client, which would turn a typed pairing failure back into
    // an opaque internal error (`core-error-copy-catalogue.md` EC-4).
    // The reason set. ⚠⚠ Every member must be registered or a client cannot parse it —
    // `SchemaRegistry` throws "No constant factory registered for type". Driven off
    // `PairingFailure.entries` so adding a member cannot be half-done: `sealedSubclasses` is
    // JVM-reflection-only and this is a KMP module, so the list is the single source of truth.
    type(
        interfaceClass = PairingFailure::class,
        dataSchema = PairingFailure.Schema,
    )
    PairingFailure.entries.forEach { reason ->
        constant(
            interfaceClass = reason::class,
            dataSchema = reason.schema,
            dtoFactory = { reason },
            roFactory = { chain: RequestChain, context: TContext ->
                TConstantRo(chain, context, PairingFailure.Schema)
            },
        )
    }

    composite(
        interfaceClass = PairingFailedError::class,
        dataSchema = PairingFailedError.Schema,
        dtoFactory = ::PairingFailedErrorDto
    )

    composite(
        interfaceClass = PairingDeviceRemovedError::class,
        dataSchema = PairingDeviceRemovedError.Schema,
        dtoFactory = { _ -> PairingDeviceRemovedErrorDto() }
    )

    composite(
        interfaceClass = PairingApproverRevokedError::class,
        dataSchema = PairingApproverRevokedError.Schema,
        dtoFactory = { _ -> PairingApproverRevokedErrorDto() }
    )

    composite(
        interfaceClass = PairingThisDeviceRemovedError::class,
        dataSchema = PairingThisDeviceRemovedError.Schema,
        dtoFactory = { _ -> PairingThisDeviceRemovedErrorDto() }
    )

    composite(
        interfaceClass = RecoveryPhraseUnreadableError::class,
        dataSchema = RecoveryPhraseUnreadableError.Schema,
        dtoFactory = { _ -> RecoveryPhraseUnreadableErrorDto() }
    )

    composite(
        interfaceClass = DeviceAlreadySetUpError::class,
        dataSchema = DeviceAlreadySetUpError.Schema,
        dtoFactory = { _ -> DeviceAlreadySetUpErrorDto() }
    )

    composite(
        interfaceClass = PairingRequest::class,
        dataSchema = PairingRequest.Schema,
        dtoFactory = ::PairingRequestDto
    )

    composite(
        interfaceClass = PairingInitiation::class,
        dataSchema = PairingInitiation.Schema,
        dtoFactory = ::PairingInitiationDto
    )

    composite(
        interfaceClass = OfferPairingSession::class,
        dataSchema = OfferPairingSession.Schema,
        dtoFactory = ::OfferPairingSessionDto
    )

    composite(
        interfaceClass = OfferStatus::class,
        dataSchema = OfferStatus.Schema,
        dtoFactory = ::OfferStatusDto
    )

    composite(
        interfaceClass = PairingOfferApprovalPrompt::class,
        dataSchema = PairingOfferApprovalPrompt.Schema,
        dtoFactory = ::PairingOfferApprovalPromptDto
    )

    composite(
        interfaceClass = UnlockStatus::class,
        dataSchema = UnlockStatus.Schema,
        dtoFactory = ::UnlockStatusDto
    )

    composite(
        interfaceClass = SyncStatus::class,
        dataSchema = SyncStatus.Schema,
        dtoFactory = ::SyncStatusDto
    )

    composite(
        interfaceClass = SyncMetadata::class,
        dataSchema = SyncMetadata.Schema,
        dtoFactory = ::SyncMetadataDto
    )

    trait(
        SyncMetadata.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        SyncMetadataDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = ResolvedDataId::class,
        dataSchema = ResolvedDataId.Schema,
        dtoFactory = ::ResolvedDataIdDto
    )

    trait(
        ResolvedDataId.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        ResolvedDataIdDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = LocalWriteMetadata::class,
        dataSchema = LocalWriteMetadata.Schema,
        dtoFactory = ::LocalWriteMetadataDto
    )

    trait(
        LocalWriteMetadata.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        LocalWriteMetadataDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = PersistedTombstone::class,
        dataSchema = PersistedTombstone.Schema,
        dtoFactory = ::PersistedTombstoneDto
    )

    trait(
        PersistedTombstone.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PersistedTombstoneDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = TombstoneTypeCounts::class,
        dataSchema = TombstoneTypeCounts.Schema,
        dtoFactory = ::TombstoneTypeCountsDto
    )

    trait(
        TombstoneTypeCounts.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        TombstoneTypeCountsDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = RekeyMigrationMarker::class,
        dataSchema = RekeyMigrationMarker.Schema,
        dtoFactory = ::RekeyMigrationMarkerDto
    )

    trait(
        RekeyMigrationMarker.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        RekeyMigrationMarkerDataIdProviderImpl(context)
    }

    composite(
        interfaceClass = PendingContentEmit::class,
        dataSchema = PendingContentEmit.Schema,
        dtoFactory = ::PendingContentEmitDto
    )

    trait(
        PendingContentEmit.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PendingContentEmitDataIdProviderImpl(context)
    }

    // A pending tombstone's cascade (`content-plane-cr2-quorumless.md` §10.7), persisted beside it
    // so a restart before the push does not lose the subtree's names. ⚠ A NEW local composite, so
    // no SyncService version moves (HR #9 bumps on a reshape); see its KDoc.
    composite(
        interfaceClass = PendingTombstoneCascade::class,
        dataSchema = PendingTombstoneCascade.Schema,
        dtoFactory = ::PendingTombstoneCascadeDto
    )

    trait(
        PendingTombstoneCascade.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PendingTombstoneCascadeDataIdProviderImpl(context)
    }

    // A reaped descendant's ordering point (`content-plane-cr2-quorumless.md` §10.12 Q4), persisted
    // so a restart does not let a stale block bring it back. ⚠ A NEW local composite: no version
    // moves.
    composite(
        interfaceClass = ReapWatermark::class,
        dataSchema = ReapWatermark.Schema,
        dtoFactory = ::ReapWatermarkDto
    )

    trait(
        ReapWatermark.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        ReapWatermarkDataIdProviderImpl(context)
    }

    // A reap this device owes (`content-plane-cr2-b3-wire-spec.md` §3.3/§5) to
    // `/sync/content/reap`, durable until the relay accepts it. ⚠ A NEW local composite: no version moves.
    composite(
        interfaceClass = PendingReapRemoval::class,
        dataSchema = PendingReapRemoval.Schema,
        dtoFactory = ::PendingReapRemovalDto
    )

    trait(
        PendingReapRemoval.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PendingReapRemovalDataIdProviderImpl(context)
    }

    // A queued block's edges (`content-plane-cr2-b3-wire-spec.md` §5), fixed from its payload at
    // emit time and pushed with it as one `EdgedPush`. ⚠ A NEW local composite: no version moves.
    composite(
        interfaceClass = PendingBlockEdges::class,
        dataSchema = PendingBlockEdges.Schema,
        dtoFactory = ::PendingBlockEdgesDto
    )

    trait(
        PendingBlockEdges.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PendingBlockEdgesDataIdProviderImpl(context)
    }

    // A refused block's revive (`content-plane-cr2-b4-spec.md` §2.2), persisted before the
    // retry so a crash does not re-upload. ⚠ A NEW local composite: no version moves.
    composite(
        interfaceClass = PendingBlockRevive::class,
        dataSchema = PendingBlockRevive.Schema,
        dtoFactory = ::PendingBlockReviveDto
    )

    trait(
        PendingBlockRevive.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        PendingBlockReviveDataIdProviderImpl(context)
    }

    // Which content a removed holder named (`content-plane-cr2-b4-spec.md` §2.3), so its removal
    // waits for the adds.
    // ⚠ A NEW local composite: no version moves.
    composite(
        interfaceClass = RemovedContents::class,
        dataSchema = RemovedContents.Schema,
        dtoFactory = ::RemovedContentsDto
    )

    trait(
        RemovedContents.Schema,
        DataIdProvider.Schema
    ) { context, _ ->
        RemovedContentsDataIdProviderImpl(context)
    }
}
