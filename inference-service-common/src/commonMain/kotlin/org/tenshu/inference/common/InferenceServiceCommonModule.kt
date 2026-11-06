package org.tenshu.inference.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.MediaLabelDataIdProvider
import org.tenshu.inference.common.impl.MediaLabelDataIdProviderImpl
import org.tenshu.inference.AvailableModel
import org.tenshu.inference.AvailableModels
import org.tenshu.inference.ClassifyIntent
import org.tenshu.inference.DeleteModel
import org.tenshu.inference.DownloadModel
import org.tenshu.inference.DownloadProgress
import org.tenshu.inference.IndexProgress
import org.tenshu.inference.MediaIndexProgress
import org.tenshu.inference.ModelDownloadProgress
import org.tenshu.inference.InstalledModel
import org.tenshu.inference.InstalledModels
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.MediaIndexPass
import org.tenshu.inference.StartMediaIndexPass
import org.tenshu.inference.ModelUnavailable
import org.tenshu.inference.IntentCandidate
import org.tenshu.inference.IntentChoice
import org.tenshu.inference.FaceGroups
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.NameFaceGroup
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.SimilarPhoto
import org.tenshu.inference.common.impl.AvailableModelDto
import org.tenshu.inference.common.impl.InstalledModelDto
import org.tenshu.inference.common.impl.IndexPassRefusedDto
import org.tenshu.inference.common.impl.MediaIndexProgressDto
import org.tenshu.inference.common.impl.MediaLabelDto
import org.tenshu.inference.common.impl.ModelDownloadProgressDto
import org.tenshu.inference.common.impl.ModelUnavailableDto
import org.tenshu.inference.common.impl.SemanticSearchUnavailableDto
import org.tenshu.inference.common.impl.IntentCandidateDto
import org.tenshu.inference.common.impl.IntentChoiceDto
import org.tenshu.inference.common.impl.FaceGroupsUnavailableDto
import org.tenshu.inference.common.impl.GroupedFaceDto
import org.tenshu.inference.common.impl.SimilarPhotoDto

/**
 * The inference service's registration (IN-3).
 *
 * ⚠ No `SchemaMigration` chain: `InferenceService` is at its v1 release baseline, so there is no
 * earlier version of it in any store to migrate from. The first bump — whenever a label's shape
 * changes — owes one.
 */
val inferenceServiceCommonModule = tModule {

    service(InferenceService.Schema)

    composite(
        interfaceClass = MediaLabel::class,
        dataSchema = MediaLabel.Schema,
        dtoFactory = ::MediaLabelDto,
    )

    // IN-4b. ⚠ `SimilarPhoto` is a RETURN shape, never a stored row — no DataId provider, no
    // `Syncable`, and nothing writes one. It needs registering all the same: an op's return type
    // that no module knows how to build comes back as an unreadable blob on the client.
    composite(
        interfaceClass = SimilarPhoto::class,
        dataSchema = SimilarPhoto.Schema,
        dtoFactory = ::SimilarPhotoDto,
    )

    // ⚠⚠ A typed error is an ordinary composite on the wire. Registering the OP but not its error
    // makes the failure path the one thing that cannot cross IPC — and it only shows up when
    // something actually fails, which is the worst moment to discover it.
    composite(
        interfaceClass = SemanticSearchUnavailable::class,
        dataSchema = SemanticSearchUnavailable.Schema,
        dtoFactory = ::SemanticSearchUnavailableDto,
    )

    // IN-6 F3. ⚠ A RETURN shape like `SimilarPhoto`, and for a sharper reason: a face GROUP is
    // rebuilt per call from device-local templates and is never stored, because storing cluster
    // identity is what would force templates onto the wire (IN-D5).
    composite(
        interfaceClass = GroupedFace::class,
        dataSchema = GroupedFace.Schema,
        dtoFactory = ::GroupedFaceDto,
    )

    // ⚠⚠ Its typed error, for the reason two blocks up — and here the failure path is the ONLY
    // path until F2 records a calibration: `faceGroups` throws `UNCALIBRATED` on every call in a
    // build without one, so an unregistered error would make the single reachable outcome the one
    // that cannot cross IPC.
    composite(
        interfaceClass = FaceGroupsUnavailable::class,
        dataSchema = FaceGroupsUnavailable.Schema,
        dtoFactory = ::FaceGroupsUnavailableDto,
    )

    // MQ-5. ⚠⚠ BOTH halves, and the param one is the easy miss: `IntentCandidate` only ever
    // travels *into* the op, so a module that registers just the return type compiles, passes a
    // proxy-side unit test, and fails at the call with "Schema not found" — which is how this was
    // found. An op's params are wire shapes exactly like its result.
    composite(
        interfaceClass = IntentCandidate::class,
        dataSchema = IntentCandidate.Schema,
        dtoFactory = ::IntentCandidateDto,
    )

    composite(
        interfaceClass = IntentChoice::class,
        dataSchema = IntentChoice.Schema,
        dtoFactory = ::IntentChoiceDto,
    )

    // IN-2c. ⚠ Both are RETURN shapes — nothing stores one and nothing filters on one — but they
    // still need registering, or an op's result comes back to the client as an unreadable blob.
    composite(
        interfaceClass = AvailableModel::class,
        dataSchema = AvailableModel.Schema,
        dtoFactory = ::AvailableModelDto,
    )

    composite(
        interfaceClass = InstalledModel::class,
        dataSchema = InstalledModel.Schema,
        dtoFactory = ::InstalledModelDto,
    )

    // ⚠⚠ The typed error, and it matters more here than anywhere else on this service: with no
    // release key every one of these four ops fails, so the error path is the ONLY path a device
    // exercises today. An unregistered error would make the single reachable outcome the one that
    // cannot cross IPC.
    composite(
        interfaceClass = ModelUnavailable::class,
        dataSchema = ModelUnavailable.Schema,
        dtoFactory = ::ModelUnavailableDto,
    )

    operation(PhotosLike.Schema)
    operation(FaceGroups.Schema)
    // IN-6 F3b. ⚠⚠ The op that makes a person's name realm data; its typed error is
    // `FaceGroupsUnavailable`, already registered above, because a refused NAME and a refused
    // READ are the same fact about the same switch.
    operation(NameFaceGroup.Schema)
    operation(ClassifyIntent.Schema)
    // 1c-3. ⚠ The tick's payload — an unregistered stream element crosses as an unreadable blob,
    // and a progress bar that never moves is indistinguishable from a stalled download.
    composite(
        interfaceClass = ModelDownloadProgress::class,
        dataSchema = ModelDownloadProgress.Schema,
        dtoFactory = ::ModelDownloadProgressDto,
    )

    // IN-D6 (1e-3). ⚠ The index pass's tick — same reasoning as the download tick above: an
    // unregistered stream element crosses as an unreadable blob, and a progress bar that never
    // moves is indistinguishable from a pass that is stuck.
    composite(
        interfaceClass = MediaIndexProgress::class,
        dataSchema = MediaIndexProgress.Schema,
        dtoFactory = ::MediaIndexProgressDto,
    )

    operation(AvailableModels.Schema)
    operation(InstalledModels.Schema)
    operation(DownloadModel.Schema)
    operation(DeleteModel.Schema)
    operation(DownloadProgress.Schema)
    operation(IndexProgress.Schema)
    // IN-D6 (1e). ⚠⚠ Registered here like any other op: `ScheduleService` refuses to schedule an
    // id it cannot resolve, so an unregistered pass would fail at REGISTRATION — loudly, at proxy
    // init — rather than silently never running. ⚠ No client RO: `HeadlessSafe` means only the
    // scheduler dispatches it.
    operation(MediaIndexPass.Schema)
    // §34. ⚠ The manual twin of the pass above, and NOT `HeadlessSafe`: it has a receiver and a
    // client RO because a person presses it, where the scheduled one is dispatched by id alone.
    operation(StartMediaIndexPass.Schema)

    // ⚠⚠ Its typed refusal. An op registered without its error makes the failure path the one thing
    // that cannot cross IPC — and a refusal is the likely outcome on a device with no model.
    composite(
        interfaceClass = IndexPassRefused::class,
        dataSchema = IndexPassRefused.Schema,
        dtoFactory = ::IndexPassRefusedDto,
    )

    trait(
        dataSchema = MediaLabel.Schema,
        traitSchema = MediaLabelDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MediaLabelDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )
}
