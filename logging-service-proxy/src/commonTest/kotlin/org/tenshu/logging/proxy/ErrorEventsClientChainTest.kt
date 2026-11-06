package org.tenshu.logging.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.fold
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.LoggingService
import org.tenshu.logging.client.loggingServiceClientModule
import org.tenshu.logging.test.createLoggingServiceCommonTestRegistry
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The Errors screen's own read, run against the registrations the real client actually has.**
 *
 * Pins that Control's Settings → Errors can build its read: without the element Ro,
 * `ErrorEventsManager.events()` dies on open with
 * `No request object factory registered for type: tenshu/logging/error-event`.
 *
 * ### ⚠⚠ Why a unit test of either module misses it
 *
 * There are **two** registrations for `ErrorEvent`, in two different modules and two different maps:
 *
 * | module | call | what it enables |
 * |---|---|---|
 * | `loggingServiceCommonModule` | `composite(…, dtoFactory = ::ErrorEventDto)` | serialising the value on the wire |
 * | `loggingServiceClientModule` | `type(…, roFactory = …)` | **building a request object for it** |
 *
 * A plain relation traversal needs only the first, so `service.events` works without the second;
 * the element-lambda ops — `sortBy`, `filter`, `search`, `find` — need both, because they invoke the
 * caller's lambda against a **synthetic element request object** to record the selector into the
 * chain (`loggingServiceClientModule` carries the contract).
 *
 * ⚠ `select { … }` is not one of them: its lambda receives a `ListBuilder<Selector<R>>`, not the
 * element. The projection is never the problem; the sort key is.
 *
 * ⚠ So this test registers **exactly** the common + client modules, and nothing else. Registering a
 * proxy's fuller registry — or letting a test registry pre-register the type — would resolve the
 * factory by a route the real client does not have, and pass without the client registration.
 */
class ErrorEventsClientChainTest {

    @Test
    fun theErrorsScreenChainBuildsAgainstTheClientRegistrations() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createLoggingServiceCommonTestRegistry(),
            coroutineScope = backgroundScope,
        )
        proxy.schemaRegistry.registerConfig(loggingServiceClientModule)

        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )

        // ⚠ The shape `ErrorEventsManager.events()` uses, verbatim: the ordered-window-fusion
        // `sortByDescending { … } → take → select`. The lambda is what needs the element Ro; an
        // assertion on the *result* would be beside the point, since the failure is at chain-build
        // time and takes the whole screen down before any row is read.
        val events = client.getService<LoggingService>(LoggingService.ID).events
            .sortByDescending { capturedAt }
            .take(EVENT_WINDOW)
            .select { fields(ErrorEvent.Fields.EVENT_ID, ErrorEvent.Fields.CAPTURED_AT) }
            .flow()
            .first()

        // An empty realm has no events; the point is that building and running the chain does not
        // throw. Reading the list also proves the window survived materialisation.
        assertEquals(0, events.fold(onSuccess = { it.toList().size }, onFailure = { -1 }))
    }

    private companion object {
        /** Mirrors `TenshuErrorEventsManager.EVENT_WINDOW`; the exact bound does not matter here. */
        const val EVENT_WINDOW = 200
    }
}
