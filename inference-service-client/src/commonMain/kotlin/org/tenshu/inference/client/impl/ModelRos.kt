package org.tenshu.inference.client.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.inference.AvailableModel
import org.tenshu.inference.AvailableModels
import org.tenshu.inference.DeleteModel
import org.tenshu.inference.StartMediaIndexPass
import org.tenshu.inference.DownloadModel
import org.tenshu.inference.InstalledModel
import org.tenshu.inference.InstalledModels

/**
 * IN-2c's four client halves. ⚠ The RO turns a call into a params map on the chain; registering the
 * operation schema in `-common` alone leaves each of these resolvable and **uncallable**.
 */
internal class AvailableModelsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), AvailableModels {

    override fun availableModels(): RequestObject {
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject<TList<AvailableModel>>(
            type = listType(AvailableModel.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}

internal class InstalledModelsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), InstalledModels {

    override fun installedModels(): RequestObject {
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject<TList<InstalledModel>>(
            type = listType(InstalledModel.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}

internal class DownloadModelRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DownloadModel {

    override fun downloadModel(modelId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(DownloadModel.Params.MODEL_ID to modelId.toTString(context)),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject<InstalledModel>(
            type = InstalledModel.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}

internal class DeleteModelRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DeleteModel {

    override fun deleteModel(modelId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(DeleteModel.Params.MODEL_ID to modelId.toTString(context)),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}

/**
 * §34 — "Index my photos now".
 *
 * ⚠ No params: the pass indexes what consent allows on this device, and everything it needs is
 * proxy-side state. The result is `Data` because the op answers *started*, never a value.
 */
internal class StartMediaIndexPassRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), StartMediaIndexPass {

    override fun startMediaIndexPass(): RequestObject {
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
