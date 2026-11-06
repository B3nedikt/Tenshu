package org.tenshu.core.common

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.SimpleComposite
import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.messageOrNull
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins `TError.messageOrNull()` (`core/Error.kt`) across the field's three shapes.
 *
 * Every concrete `TError` in `:core` declares `message` mandatory (`GenericError`,
 * `NotFoundError`, `InvalidCallerError`, plus `AlreadyExistsError` in
 * `:file-service`), and `CompositeDto.getOptionalField` is strict — it throws
 * "Field … is mandatory … Use getField() instead." on a mandatory field. A helper
 * that calls [Composite.getOptionalField] on `"${id}/message"` unconditionally
 * therefore throws out of the IPC failure-log path on every `TResult.Failure`,
 * which under the uncaught `RemoteBinaryTaskExecutor.roundTrip` site brings down
 * the Drive process (e.g. on uploading a duplicate file).
 *
 * The three branches: mandatory field (the crash case), optional field, and
 * absent field (the KDoc's "marker-only error type or sentinel").
 */
class ErrorMessageOrNullTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setup() {
        tContext = createTestContext()
    }

    @Test
    fun mandatoryMessage_returnsTheMessage_doesNotThrow() {
        val error = GenericError.create(tContext, "boom")

        // An unconditional `getOptionalField` throws `IllegalStateException: Field
        // tenshu/result/error/generic/message is mandatory in
        // tenshu/result/error/generic. Use getField() instead.` here.
        assertEquals("boom", error.messageOrNull())
    }

    @Test
    fun noMessageField_returnsNull() {
        val markerSchema = composite(
            id = "test/error/marker",
            parent = TError.Schema
        )
        val markerError = TestTError(markerSchema, emptyMap())

        assertNull(markerError.messageOrNull())
    }

    @Test
    fun optionalMessage_present_returnsValue() {
        val fieldId = "test/error/optionalMessage/message"
        val schema = composite(
            id = "test/error/optionalMessage",
            parent = TError.Schema,
            fields = {
                string(fieldId, isOptional = true)
            }
        )
        val error = TestTError(schema, mapOf(fieldId to "later".toTString()))

        assertEquals("later", error.messageOrNull())
    }

    @Test
    fun optionalMessage_absent_returnsNull() {
        val fieldId = "test/error/optionalMessage/message"
        val schema = composite(
            id = "test/error/optionalMessage",
            parent = TError.Schema,
            fields = {
                string(fieldId, isOptional = true)
            }
        )
        val error = TestTError(schema, emptyMap())

        assertNull(error.messageOrNull())
    }

    /**
     * Resolves the diamond between [SimpleComposite] (which owns `schema` as
     * a constructor val) and [TError] (which provides a default `schema`
     * via its interface) — Kotlin requires an explicit override at the
     * subclass when both implementations are inherited.
     */
    private class TestTError(
        override val schema: CompositeSchema,
        fieldValues: Map<String, Data?>
    ) : SimpleComposite(schema, fieldValues), TError
}
