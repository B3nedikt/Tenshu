package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.TStringRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestChain

internal class FilePathRo(
    requestChain: RequestChain,
    context: TContext
) : TStringRo(requestChain, context), FilePath {
    override val segments: List<String>
        get() = error("FilePath segments not available on RO")
}
