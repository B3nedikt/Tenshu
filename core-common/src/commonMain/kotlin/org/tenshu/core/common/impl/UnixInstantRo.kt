package org.tenshu.core.common.impl

import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.time.UnixInstant

/**
 * Chain-recording placeholder for a [UnixInstant]-typed field access — minted
 * when a selector lambda reads a timestamp field off an element RO (e.g.
 * `notifications.sortByDescending { timestamp }`). Mirrors [TIntegerRo]; like
 * every `TValueRo` it carries no value, only the recorded [RequestChain].
 */
class UnixInstantRo(
    requestChain: RequestChain,
    context: TContext
) : TValueRo(requestChain, context), UnixInstant {
    override fun get(): Long = error("Not a data object")
    override fun toEpochMilliseconds(): Long = error("Not a data object")
}
