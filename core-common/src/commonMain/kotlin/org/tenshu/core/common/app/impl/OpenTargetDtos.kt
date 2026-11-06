package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.OpenOutcome
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema

internal class OpenOutcomeDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), OpenOutcome {

    override val schema: CompositeSchema get() = OpenOutcome.Schema
}

/**
 * ⚠⚠ The **parameter** type's DTO, and the one easiest to forget: `OpenTargetRequest` only ever
 * travels *into* the receiving app, so nothing's `returnType` mentions it. MQ-5a lost 14 tests to
 * exactly this omission on `IntentCandidate` — a type that only goes one way is a wire shape all
 * the same.
 */
internal class OpenTargetRequestDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), OpenTargetRequest {

    override val schema: CompositeSchema get() = OpenTargetRequest.Schema
}

/**
 * ⚠⚠ A typed error is an ordinary composite on the wire. Registering the op but not its error
 * makes the failure path the one thing that cannot cross IPC — and it only shows up when something
 * actually fails, which is the worst moment to discover it.
 */
internal class OpenTargetUnavailableDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), OpenTargetUnavailable {

    override val schema: CompositeSchema get() = OpenTargetUnavailable.Schema
}
