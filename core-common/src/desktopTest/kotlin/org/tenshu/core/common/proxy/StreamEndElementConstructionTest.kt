package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.StreamElement
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.core.dataFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Pins down a runtime `ArrayStoreException` in building a stream's end element:
 * a stream's `onUpdate` exception path builds a `StreamElement.End` with
 * `[TInteger(streamId), GenericError]` as fields. Without an explicit type on
 * the surrounding `listOf(...)`, Kotlin's vararg inference picks the second
 * arg's concrete type (`GenericError` / `TError`) as the array element type,
 * and at runtime the `TIntegerDto` returned by `createValue` then fails to
 * store into that typed array.
 *
 * The crash:
 * ```
 * java.lang.ArrayStoreException: org.tenshu.core.common.impl.TIntegerDto
 *   cannot be stored in an array of type org.tenshu.core.GenericError[]
 *     at ExecutionContext$setDatabaseListener$onUpdate$1.invokeSuspend(ExecutionContext.kt:293)
 * ```
 *
 * Both halves of the test live here so the coverage stays symmetric:
 * explicitly capture that the untyped form *does* throw on JVM, and that the
 * typed form (`listOf<Data?>(...)`) does not.
 */
class StreamEndElementConstructionTest {

    private fun newContext() = TestProxy(
        schemaRegistry = createCoreTestRegistry(),
        coroutineScope = CoroutineScope(Dispatchers.Default)
    ).tContext

    @Test
    fun buggyListOf_throwsArrayStoreException() {
        val context = newContext()
        val streamId = 1L

        // No explicit `<Data?>` — Kotlin infers `GenericError[]` for the vararg
        // array and the runtime store of the `TIntegerDto` then throws.
        assertFailsWith<ArrayStoreException> {
            context.dataFactory().createComposite<StreamElement<*>>(
                type = StreamElement.End.ID,
                fields = listOf(
                    context.dataFactory().createValue("tenshu/core/number/integer", streamId),
                    GenericError.create(context, "test")
                )
            )
        }
    }

    @Test
    fun fixedListOf_buildsEndElementSuccessfully() {
        val context = newContext()
        val streamId = 1L

        // Explicit `<Data?>` pins the vararg array element to `Data` — both
        // `TIntegerDto` and `GenericError` fit, no `ArrayStoreException`.
        val element = context.dataFactory().createComposite<StreamElement<*>>(
            type = StreamElement.End.ID,
            fields = listOf<Data?>(
                context.dataFactory().createValue("tenshu/core/number/integer", streamId),
                GenericError.create(context, "test")
            )
        )

        assertNotNull(element)
    }
}
