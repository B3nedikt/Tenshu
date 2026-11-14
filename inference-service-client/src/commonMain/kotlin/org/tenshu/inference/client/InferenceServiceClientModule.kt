package org.tenshu.inference.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.AvailableModels
import org.tenshu.inference.DeleteModel
import org.tenshu.inference.DownloadModel
import org.tenshu.inference.DownloadProgress
import org.tenshu.inference.IndexProgress
import org.tenshu.inference.InferenceService
import org.tenshu.inference.InstalledModel
import org.tenshu.inference.InstalledModels
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.FaceGroups
import org.tenshu.inference.NameFaceGroup
import org.tenshu.inference.FaceSuggestionRejections
import org.tenshu.inference.RejectFaceSuggestion
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.StartMediaIndexPass
import org.tenshu.inference.client.impl.InferenceServiceRo
import org.tenshu.inference.client.impl.AvailableModelsRo
import org.tenshu.inference.client.impl.DeleteModelRo
import org.tenshu.inference.client.impl.StartMediaIndexPassRo
import org.tenshu.inference.client.impl.DownloadModelRo
import org.tenshu.inference.client.impl.DownloadProgressRo
import org.tenshu.inference.client.impl.IndexProgressRo
import org.tenshu.inference.client.impl.InstalledModelsRo
import org.tenshu.inference.client.impl.FaceGroupsRo
import org.tenshu.inference.client.impl.NameFaceGroupRo
import org.tenshu.inference.client.impl.FaceSuggestionRejectionsRo
import org.tenshu.inference.client.impl.RejectFaceSuggestionRo
import org.tenshu.inference.client.impl.PhotosLikeRo

val inferenceServiceClientModule = tModule {

    type(
        interfaceClass = MediaLabel::class,
        dataSchema = MediaLabel.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row when it
        // is READ; this builds the RECEIVER a chain-DSL predicate lambda runs against, so
        // registering only the former leaves the type readable and **unfilterable** —
        // `filter<MediaLabel> { … }` dies casting `CompositeRo`, a long way from the registration
        // that caused it. MQ-2 lost a day to exactly this on three types at once.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, MediaLabel.Schema), MediaLabel {
                override val schema: CompositeSchema get() = MediaLabel.Schema
            }
        },
    )

    // IN-4b. ⚠ The RO is the CLIENT half of the op: it turns a call into a params map on the
    // chain. Registering the operation schema in `-common` alone leaves `photosLike` resolvable
    // and uncallable.
    operation(
        interfaceClass = PhotosLike::class,
        protocolSchema = PhotosLike.Schema,
        roFactory = ::PhotosLikeRo,
    )

    // IN-6 F3. ⚠⚠ The same two-sided registration, and the miss costs a whole afternoon: with only
    // the `-common` half, `faceGroups` resolves, compiles, and throws "Operation not found" at the
    // call — which reads like a wiring bug in the proxy rather than a missing RO.
    operation(
        interfaceClass = FaceGroups::class,
        protocolSchema = FaceGroups.Schema,
        roFactory = ::FaceGroupsRo,
    )

    operation(
        interfaceClass = NameFaceGroup::class,
        protocolSchema = NameFaceGroup.Schema,
        roFactory = ::NameFaceGroupRo,
    )

    // IN-6 F6c name suggestions — both halves registered, for the reason above.
    operation(
        interfaceClass = RejectFaceSuggestion::class,
        protocolSchema = RejectFaceSuggestion.Schema,
        roFactory = ::RejectFaceSuggestionRo,
    )

    operation(
        interfaceClass = FaceSuggestionRejections::class,
        protocolSchema = FaceSuggestionRejections.Schema,
        roFactory = ::FaceSuggestionRejectionsRo,
    )

    // ⚠ `SimilarPhoto` needs no `roFactory`, unlike `MediaLabel` above: it is only ever READ back
    // out of an op result, never used as the receiver of a chain-DSL predicate lambda. The
    // `dtoFactory` in `-common` is the whole registration.

    // IN-2c. ⚠⚠ `InstalledModel` needs a TYPED request object because `downloadModel` returns ONE
    // of them rather than a list: the chain has to build a request object *of that type*, and
    // without this the call dies with `No request object factory registered for type` — at the call
    // site, a long way from the registration that caused it.
    //
    // ⚠ `AvailableModel` needs none, and the difference is exactly this: it is only ever reached
    // inside a `TList` result, where the list's own factory builds the members. `SimilarPhoto` is
    // the same case. **"Is it returned bare, or inside a list?"** is the question that decides,
    // not "is it a return type".
    type(
        interfaceClass = InstalledModel::class,
        dataSchema = InstalledModel.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, InstalledModel.Schema), InstalledModel {
                override val schema: CompositeSchema get() = InstalledModel.Schema
            }
        },
    )

    // IN-2c — the four Control reads through.
    operation(
        interfaceClass = AvailableModels::class,
        protocolSchema = AvailableModels.Schema,
        roFactory = ::AvailableModelsRo,
    )

    operation(
        interfaceClass = InstalledModels::class,
        protocolSchema = InstalledModels.Schema,
        roFactory = ::InstalledModelsRo,
    )

    operation(
        interfaceClass = DownloadModel::class,
        protocolSchema = DownloadModel.Schema,
        roFactory = ::DownloadModelRo,
    )

    operation(
        interfaceClass = DeleteModel::class,
        protocolSchema = DeleteModel.Schema,
        roFactory = ::DeleteModelRo,
    )

    // §34. ⚠ The scheduled `MediaIndexPass` has no RO and must not gain one — it is `HeadlessSafe`,
    // dispatched by id. This is its manual twin, which a person presses.
    operation(
        interfaceClass = StartMediaIndexPass::class,
        protocolSchema = StartMediaIndexPass.Schema,
        roFactory = ::StartMediaIndexPassRo,
    )

    // 1c-3 — the stream op. ⚠ `ModelDownloadProgress` needs no `roFactory`: it is only ever read
    // out of a pushed stream element, never used as a chain-DSL receiver, and never returned bare.
    operation(
        interfaceClass = DownloadProgress::class,
        protocolSchema = DownloadProgress.Schema,
        roFactory = ::DownloadProgressRo,
    )

    // 1e-3 — the index pass's stream op. ⚠ `MediaIndexProgress` needs no `roFactory` for the same
    // reason `ModelDownloadProgress` does not: it is only ever read out of a pushed stream element,
    // never used as a chain-DSL receiver, and never returned bare.
    operation(
        interfaceClass = IndexProgress::class,
        protocolSchema = IndexProgress.Schema,
        roFactory = ::IndexProgressRo,
    )

    service(
        interfaceClass = InferenceService::class,
        serviceSchema = InferenceService.Schema,
        roFactory = ::InferenceServiceRo,
    )
}
