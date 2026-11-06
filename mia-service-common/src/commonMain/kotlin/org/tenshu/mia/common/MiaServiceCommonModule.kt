package org.tenshu.mia.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaAction
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaService
import org.tenshu.mia.common.impl.MiaActionDto
import org.tenshu.mia.common.impl.MiaAnswerDto
import org.tenshu.mia.common.impl.MiaHitDto

/**
 * Schema registration for Mia's query layer (`mia-query-layer.md`).
 *
 * ⚠⚠ **The two composites are registered *here*, not only in the client module, and
 * that is the whole point of this file.** `type(...)` in a `*ClientModule` writes
 * `requestObjectFactories` and never `compositeFactories`, so a composite registered
 * only there **cannot be built** — and the **proxy** is what builds it to answer the op.
 * Nothing catches the miss: no version moves, and a caller that folds the failure to an
 * empty result hides it. The rule: *a composite an operation returns belongs in the
 * `*CommonModule` with a `dtoFactory`; a client registration is additional, never
 * sufficient.*
 *
 * ⚠ Neither type carries a `DataIdProvider` or a `Syncable` trait — they are return
 * shapes, computed per call, so nothing can persist or sync one by accident. Mia stores
 * nothing (see [MiaService]).
 */
val miaServiceCommonModule = tModule {

    composite(
        interfaceClass = MiaHit::class,
        dataSchema = MiaHit.Schema,
        dtoFactory = ::MiaHitDto,
    )

    composite(
        interfaceClass = MiaAction::class,
        dataSchema = MiaAction.Schema,
        dtoFactory = ::MiaActionDto,
    )

    composite(
        interfaceClass = MiaAnswer::class,
        dataSchema = MiaAnswer.Schema,
        dtoFactory = ::MiaAnswerDto,
    )

    service(MiaService.Schema)
}
