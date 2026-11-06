package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.UnknownParameter
import org.tenshu.core.WrongParameter
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Phase 5.7 of `docs/adr/0009-operation-schema-typing.md`: `OperationDto` rejects construction
 * when params have unknown keys or values whose schema isn't a subtype of the declared
 * field type.
 *
 * Phase 2 of `docs/proposals/core-error-channel.md`: rejections are typed
 * `TenshuException(TError)` carrying machine-readable param identifiers, not
 * freetext `IllegalArgumentException`.
 */
class OperationDtoValidationTest {

    @Test
    fun unknownParamKey_isRejectedAtConstruction() {
        val ex = assertFailsWith<TenshuException> {
            OperationDto(
                params = mapOf("not-a-real-field-id" to "x".toTString()),
                schema = Drop.Schema
            )
        }
        val error = assertIs<UnknownParameter>(ex.error)
        assertEquals("not-a-real-field-id", error.name.get())
        assertEquals(Drop.ID, error.operationId.get())
    }

    @Test
    fun valueTypeMismatch_isRejectedAtConstruction() {
        // Drop.Schema declares `Drop.Params.N` as TInteger; passing a TString triggers
        // the per-value subtype assertion.
        val ex = assertFailsWith<TenshuException> {
            OperationDto(
                params = mapOf<String, Data>(
                    Drop.Params.N to "10".toTString(),
                ),
                schema = Drop.Schema
            )
        }
        val error = assertIs<WrongParameter>(ex.error)
        assertEquals(Drop.Params.N, error.name.get())
        assertEquals(TString.ID, error.actualTypeId.get())
        assertEquals(TInteger.ID, error.expectedTypeId.get())
    }

    @Test
    fun knownParamKey_withCompatibleValue_isAccepted() {
        OperationDto(
            params = mapOf<String, Data>(
                Drop.Params.N to 10.toTInteger(),
            ),
            schema = Drop.Schema
        )
    }

    @Test
    fun emptyParams_isAccepted() {
        // Partial chain entries that haven't filled in their params yet are valid;
        // execute-time presence enforcement lives in `evaluateCallstack`.
        OperationDto(
            params = emptyMap(),
            schema = Drop.Schema
        )
    }
}
