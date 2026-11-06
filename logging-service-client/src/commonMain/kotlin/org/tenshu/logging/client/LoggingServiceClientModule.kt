package org.tenshu.logging.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.LoggingService
import org.tenshu.logging.client.impl.LoggingServiceRo

/**
 * Client-side wiring for `:logging-service` (`docs/proposals/core-error-channel.md`).
 * Registers the `LoggingService` Ro so clients can traverse `service.events` via
 * the chain DSL. No client-issued operations — the recorder is proxy-side.
 */
val loggingServiceClientModule = tModule {

    service(
        interfaceClass = LoggingService::class,
        serviceSchema = LoggingService.Schema,
        roFactory = ::LoggingServiceRo
    )

    /**
     * ⚠⚠ **A service Ro is not enough the moment a caller writes a lambda over the elements.**
     * The element-lambda ops — `sortBy`/`sortByDescending`/`thenBy`, `filter`, `search`/`searchAll`,
     * `find` — build their chain by invoking the caller's lambda against a **synthetic element
     * request object**, so the client must be able to *create* one for the element type. A plain
     * relation traversal never needs that, so the gap is invisible until a sort key appears:
     * without this registration `ErrorEventsManager.events()`'s `sortByDescending { capturedAt }`
     * kills Settings → Errors on open with
     * `No request object factory registered for type: tenshu/logging/error-event`.
     *
     * ⚠ `select { … }` is **not** in that set, despite taking a lambda: its receiver is a
     * `ListBuilder<Selector<R>>`, never the element. So `service.events.select { fields(…) }` works
     * without this; a *sort key* lambda does not.
     *
     * ⚠⚠ **And it must be a *typed* Ro, not a bare `CompositeRo`.** The lambda's receiver is
     * `ErrorEvent`, so the synthetic element is cast to it; a generic `CompositeRo` does not implement
     * the interface and the crash merely moves to a `ClassCastException`. `ErrorEvent`'s accessors are
     * all defaults over `getField`, so implementing the interface on top of [CompositeRo] is the whole
     * of it — the same shape `FileServiceClientModule` uses for `File`.
     *
     * ⚠ `loggingServiceCommonModule` already registers `ErrorEvent` via `composite(…)` — but for the
     * **DTO** only. That call takes an *optional* `roFactory` too (several services register both
     * there, e.g. `DeviceSettingRo`), so the presence of a `composite(…)` is no evidence that an Ro
     * exists; check which factories it actually passes.
     */
    type(
        interfaceClass = ErrorEvent::class,
        dataSchema = ErrorEvent.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, ErrorEvent.Schema), ErrorEvent {
                override val schema: CompositeSchema get() = ErrorEvent.Schema
            }
        }
    )
}
