package org.tenshu.sync.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.AwaitPairing
import org.tenshu.sync.CancelPairing
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.ClearBiometricSetup
import org.tenshu.sync.RelaxToHeadless
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.CreateSharedRealm
import org.tenshu.sync.SyncNow
import org.tenshu.sync.GrantSharedRealmMembership
import org.tenshu.sync.AcceptSharedRealmGrant
import org.tenshu.sync.DeclinePendingGrant
import org.tenshu.sync.PollPendingGrants
import org.tenshu.sync.RemoveSharedRealmMember
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.CloseContentStream
import org.tenshu.sync.GetRecoveryPhrase
import org.tenshu.sync.OpenContentStream
import org.tenshu.sync.ReadContentRange
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.GetSyncStatus
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.GetUnlockStatus
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.OfferHubPairing
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.ReadmitDevice
import org.tenshu.sync.ReregisterDevice
import org.tenshu.sync.RespondToHubPairing
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.ForgetThisDevice
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.LockRealm
import org.tenshu.sync.SetUpBiometricUnlock
import org.tenshu.sync.SetUpLocalUnlock
import org.tenshu.sync.SetUpUnlock
import org.tenshu.sync.StartPairing
import org.tenshu.sync.StopPairing
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.UnlockBiometric
import org.tenshu.sync.UnlockLocal
import org.tenshu.sync.client.impl.ApprovePairingOfferRo
import org.tenshu.sync.client.impl.ApprovePairingRequestRo
import org.tenshu.sync.client.impl.AwaitPairingRo
import org.tenshu.sync.client.impl.CancelPairingOfferRo
import org.tenshu.sync.client.impl.CancelPairingRo
import org.tenshu.sync.client.impl.ClearBiometricSetupRo
import org.tenshu.sync.client.impl.RelaxToHeadlessRo
import org.tenshu.sync.client.impl.DenyPairingOfferRo
import org.tenshu.sync.client.impl.CreateRealmRo
import org.tenshu.sync.client.impl.ReadmitDeviceRo
import org.tenshu.sync.client.impl.ReregisterDeviceRo
import org.tenshu.sync.client.impl.CreateSharedRealmRo
import org.tenshu.sync.client.impl.SyncNowRo
import org.tenshu.sync.client.impl.GrantSharedRealmMembershipRo
import org.tenshu.sync.client.impl.AcceptSharedRealmGrantRo
import org.tenshu.sync.client.impl.DeclinePendingGrantRo
import org.tenshu.sync.client.impl.PollPendingGrantsRo
import org.tenshu.sync.client.impl.RemoveSharedRealmMemberRo
import org.tenshu.sync.client.impl.GetChangesRo
import org.tenshu.sync.client.impl.GetPairingStatusRo
import org.tenshu.sync.client.impl.GetPendingPairingRequestsRo
import org.tenshu.sync.client.impl.CloseContentStreamRo
import org.tenshu.sync.client.impl.GetRecoveryPhraseRo
import org.tenshu.sync.client.impl.OpenContentStreamRo
import org.tenshu.sync.client.impl.ReadContentRangeRo
import org.tenshu.sync.client.impl.GetSyncStatusRo
import org.tenshu.sync.client.impl.GetSyncLogBeforeRo
import org.tenshu.sync.client.impl.GetSyncLogRo
import org.tenshu.sync.client.impl.GetTombstonesRo
import org.tenshu.sync.client.impl.GetUnlockStatusRo
import org.tenshu.sync.client.impl.GetOfferStatusRo
import org.tenshu.sync.client.impl.InitiatePairingRo
import org.tenshu.sync.client.impl.OfferHubPairingRo
import org.tenshu.sync.client.impl.OfferPairingRo
import org.tenshu.sync.client.impl.PushBlockRo
import org.tenshu.sync.client.impl.PushTombstoneRo
import org.tenshu.sync.client.impl.RecoveryClaimRo
import org.tenshu.sync.client.impl.LockRealmRo
import org.tenshu.sync.client.impl.RespondToHubPairingRo
import org.tenshu.sync.client.impl.RespondToPairingOfferRo
import org.tenshu.sync.client.impl.ForgetThisDeviceRo
import org.tenshu.sync.client.impl.RevokeDeviceRo
import org.tenshu.sync.client.impl.SetUpBiometricUnlockRo
import org.tenshu.sync.client.impl.SetUpLocalUnlockRo
import org.tenshu.sync.client.impl.SetUpUnlockRo
import org.tenshu.sync.client.impl.StartPairingRo
import org.tenshu.sync.client.impl.TaskServiceRo
import org.tenshu.sync.client.impl.RegisterTaskRo
import org.tenshu.sync.client.impl.RenewTaskLeaseRo
import org.tenshu.sync.client.impl.TransitionTaskRo
import org.tenshu.sync.client.impl.StopPairingRo
import org.tenshu.sync.client.impl.SyncServiceRo
import org.tenshu.sync.client.impl.UnlockBiometricRo
import org.tenshu.sync.client.impl.UnlockLocalRo

val syncServiceClientModule = tModule {

    type(
        interfaceClass = SyncBlock::class,
        dataSchema = SyncBlock.Schema
    )

    // Task composite (TS-3/TS-4): the data composites the TaskService ops
    // return / take as params need CLIENT-side request-object registration —
    // the common module's composite(...) covers serialization only (the
    // WorkoutStreams "client-module registration" lesson, surfaced by the
    // first real client invocation in TS-4).
    type(
        interfaceClass = org.tenshu.core.TaskSpec::class,
        dataSchema = org.tenshu.core.TaskSpec.Schema
    )
    type(
        interfaceClass = org.tenshu.sync.GenericTaskSpec::class,
        dataSchema = org.tenshu.sync.GenericTaskSpec.Schema
    )
    type(
        interfaceClass = org.tenshu.sync.BackgroundTask::class,
        dataSchema = org.tenshu.sync.BackgroundTask.Schema
    )

    type(
        interfaceClass = SyncService::class,
        dataSchema = SyncService.Schema,
        roFactory = ::SyncServiceRo
    )

    // Task composite (core-task-composite.md §5, TS-3).
    type(
        interfaceClass = org.tenshu.sync.TaskService::class,
        dataSchema = org.tenshu.sync.TaskService.Schema,
        roFactory = ::TaskServiceRo
    )

    operation(
        interfaceClass = org.tenshu.sync.RegisterTask::class,
        protocolSchema = org.tenshu.sync.RegisterTask.Schema,
        roFactory = ::RegisterTaskRo
    )

    operation(
        interfaceClass = org.tenshu.sync.RenewTaskLease::class,
        protocolSchema = org.tenshu.sync.RenewTaskLease.Schema,
        roFactory = ::RenewTaskLeaseRo
    )

    operation(
        interfaceClass = org.tenshu.sync.TransitionTask::class,
        protocolSchema = org.tenshu.sync.TransitionTask.Schema,
        roFactory = ::TransitionTaskRo
    )

    operation(
        interfaceClass = PushBlock::class,
        protocolSchema = PushBlock.Schema,
        roFactory = ::PushBlockRo
    )

    operation(
        interfaceClass = GetChanges::class,
        protocolSchema = GetChanges.Schema,
        roFactory = ::GetChangesRo
    )

    operation(
        interfaceClass = PushTombstone::class,
        protocolSchema = PushTombstone.Schema,
        roFactory = ::PushTombstoneRo
    )

    operation(
        interfaceClass = GetTombstones::class,
        protocolSchema = GetTombstones.Schema,
        roFactory = ::GetTombstonesRo
    )

    operation(
        interfaceClass = GetSyncLog::class,
        protocolSchema = GetSyncLog.Schema,
        roFactory = ::GetSyncLogRo
    )

    operation(
        interfaceClass = GetSyncLogBefore::class,
        protocolSchema = GetSyncLogBefore.Schema,
        roFactory = ::GetSyncLogBeforeRo
    )

    operation(
        interfaceClass = StartPairing::class,
        protocolSchema = StartPairing.Schema,
        roFactory = ::StartPairingRo
    )

    operation(
        interfaceClass = StopPairing::class,
        protocolSchema = StopPairing.Schema,
        roFactory = ::StopPairingRo
    )

    operation(
        interfaceClass = GetSyncStatus::class,
        protocolSchema = GetSyncStatus.Schema,
        roFactory = ::GetSyncStatusRo
    )

    operation(
        interfaceClass = GetPendingPairingRequests::class,
        protocolSchema = GetPendingPairingRequests.Schema,
        roFactory = ::GetPendingPairingRequestsRo
    )

    operation(
        interfaceClass = ApprovePairingRequest::class,
        protocolSchema = ApprovePairingRequest.Schema,
        roFactory = ::ApprovePairingRequestRo
    )

    operation(
        interfaceClass = GetRecoveryPhrase::class,
        protocolSchema = GetRecoveryPhrase.Schema,
        roFactory = ::GetRecoveryPhraseRo
    )

    // D2b streaming pull ops (drive-streaming-preview.md §3.5). OpenContentStreamRo
    // erases its return to `Data` (see that class), so — like every other terminal-op
    // RO — the concrete ContentStreamHandle needs no client-side request-object
    // factory; its DTO/composite factory in syncServiceCommonModule materialises it.
    operation(
        interfaceClass = OpenContentStream::class,
        protocolSchema = OpenContentStream.Schema,
        roFactory = ::OpenContentStreamRo
    )

    operation(
        interfaceClass = ReadContentRange::class,
        protocolSchema = ReadContentRange.Schema,
        roFactory = ::ReadContentRangeRo
    )

    operation(
        interfaceClass = CloseContentStream::class,
        protocolSchema = CloseContentStream.Schema,
        roFactory = ::CloseContentStreamRo
    )

    operation(
        interfaceClass = InitiatePairing::class,
        protocolSchema = InitiatePairing.Schema,
        roFactory = ::InitiatePairingRo
    )

    operation(
        interfaceClass = GetPairingStatus::class,
        protocolSchema = GetPairingStatus.Schema,
        roFactory = ::GetPairingStatusRo
    )

    operation(
        interfaceClass = AwaitPairing::class,
        protocolSchema = AwaitPairing.Schema,
        roFactory = ::AwaitPairingRo
    )

    // Inverted-direction pairing (sync-pairing-inverted-direction.md §3.3): the
    // offerer-side ops the apex "Show a code" flow dispatches (offer + poll +
    // cancel) plus the responder-side ops (respond / approve / deny). Their
    // schemas + proxy impls already existed; without these client RO factories
    // `findOperation<OfferPairing>(…)` returned null → "Operation … not found".
    operation(
        interfaceClass = OfferPairing::class,
        protocolSchema = OfferPairing.Schema,
        roFactory = ::OfferPairingRo
    )

    operation(
        interfaceClass = GetOfferStatus::class,
        protocolSchema = GetOfferStatus.Schema,
        roFactory = ::GetOfferStatusRo
    )

    operation(
        interfaceClass = CancelPairingOffer::class,
        protocolSchema = CancelPairingOffer.Schema,
        roFactory = ::CancelPairingOfferRo
    )

    operation(
        interfaceClass = RespondToPairingOffer::class,
        protocolSchema = RespondToPairingOffer.Schema,
        roFactory = ::RespondToPairingOfferRo
    )

    // Headless hub pairing (hub proposal §11, H2): the hub-side arming op + the
    // phone-side printed-label responder entry. Approve/deny reuse the offer-flow
    // ops above unchanged.
    operation(
        interfaceClass = OfferHubPairing::class,
        protocolSchema = OfferHubPairing.Schema,
        roFactory = ::OfferHubPairingRo
    )

    operation(
        interfaceClass = RespondToHubPairing::class,
        protocolSchema = RespondToHubPairing.Schema,
        roFactory = ::RespondToHubPairingRo
    )

    operation(
        interfaceClass = ApprovePairingOffer::class,
        protocolSchema = ApprovePairingOffer.Schema,
        roFactory = ::ApprovePairingOfferRo
    )

    operation(
        interfaceClass = DenyPairingOffer::class,
        protocolSchema = DenyPairingOffer.Schema,
        roFactory = ::DenyPairingOfferRo
    )

    operation(
        interfaceClass = CreateRealm::class,
        protocolSchema = CreateRealm.Schema,
        roFactory = ::CreateRealmRo
    )

    operation(
        interfaceClass = ReregisterDevice::class,
        protocolSchema = ReregisterDevice.Schema,
        roFactory = ::ReregisterDeviceRo
    )

    operation(
        interfaceClass = ReadmitDevice::class,
        protocolSchema = ReadmitDevice.Schema,
        roFactory = ::ReadmitDeviceRo
    )

    operation(
        interfaceClass = CreateSharedRealm::class,
        protocolSchema = CreateSharedRealm.Schema,
        roFactory = ::CreateSharedRealmRo
    )

    operation(
        interfaceClass = SyncNow::class,
        protocolSchema = SyncNow.Schema,
        roFactory = ::SyncNowRo
    )

    operation(
        interfaceClass = GrantSharedRealmMembership::class,
        protocolSchema = GrantSharedRealmMembership.Schema,
        roFactory = ::GrantSharedRealmMembershipRo
    )

    operation(
        interfaceClass = AcceptSharedRealmGrant::class,
        protocolSchema = AcceptSharedRealmGrant.Schema,
        roFactory = ::AcceptSharedRealmGrantRo
    )

    operation(
        interfaceClass = RemoveSharedRealmMember::class,
        protocolSchema = RemoveSharedRealmMember.Schema,
        roFactory = ::RemoveSharedRealmMemberRo
    )

    operation(
        interfaceClass = PollPendingGrants::class,
        protocolSchema = PollPendingGrants.Schema,
        roFactory = ::PollPendingGrantsRo
    )

    operation(
        interfaceClass = DeclinePendingGrant::class,
        protocolSchema = DeclinePendingGrant.Schema,
        roFactory = ::DeclinePendingGrantRo
    )

    operation(
        interfaceClass = RecoveryClaim::class,
        protocolSchema = RecoveryClaim.Schema,
        roFactory = ::RecoveryClaimRo
    )

    operation(
        interfaceClass = RevokeDevice::class,
        protocolSchema = RevokeDevice.Schema,
        roFactory = ::RevokeDeviceRo
    )

    operation(
        interfaceClass = ForgetThisDevice::class,
        protocolSchema = ForgetThisDevice.Schema,
        roFactory = ::ForgetThisDeviceRo
    )

    operation(
        interfaceClass = GetUnlockStatus::class,
        protocolSchema = GetUnlockStatus.Schema,
        roFactory = ::GetUnlockStatusRo
    )

    operation(
        interfaceClass = SetUpLocalUnlock::class,
        protocolSchema = SetUpLocalUnlock.Schema,
        roFactory = ::SetUpLocalUnlockRo
    )

    operation(
        interfaceClass = SetUpUnlock::class,
        protocolSchema = SetUpUnlock.Schema,
        roFactory = ::SetUpUnlockRo
    )

    operation(
        interfaceClass = UnlockLocal::class,
        protocolSchema = UnlockLocal.Schema,
        roFactory = ::UnlockLocalRo
    )

    operation(
        interfaceClass = LockRealm::class,
        protocolSchema = LockRealm.Schema,
        roFactory = ::LockRealmRo
    )

    operation(
        interfaceClass = SetUpBiometricUnlock::class,
        protocolSchema = SetUpBiometricUnlock.Schema,
        roFactory = ::SetUpBiometricUnlockRo
    )

    operation(
        interfaceClass = UnlockBiometric::class,
        protocolSchema = UnlockBiometric.Schema,
        roFactory = ::UnlockBiometricRo
    )

    operation(
        interfaceClass = ClearBiometricSetup::class,
        protocolSchema = ClearBiometricSetup.Schema,
        roFactory = ::ClearBiometricSetupRo
    )

    operation(
        interfaceClass = RelaxToHeadless::class,
        protocolSchema = RelaxToHeadless.Schema,
        roFactory = ::RelaxToHeadlessRo
    )

    operation(
        interfaceClass = CancelPairing::class,
        protocolSchema = CancelPairing.Schema,
        roFactory = ::CancelPairingRo
    )
}
