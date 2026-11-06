package org.tenshu.core.common.proxy

import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.SyntheticTestData.Companion.Field
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.propertyRef
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests the chain→read projection capture used by [evaluateCallstack] at the
 * array-materialisation boundary (see [captureProjection]).
 */
class CaptureProjectionTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
    }

    private fun selectOp(vararg fieldIds: String) = OperationDto(
        params = mapOf(
            Select.Params.SELECTORS to TListDto(
                fieldIds.map {
                    Selector.FieldSelector.create<SyntheticTestData>(
                        testProxy.tContext,
                        fieldReference = fieldRef(it),
                    )
                }
            )
        ),
        schema = Select.Schema,
    )

    /** A `select` directly materialising the array ⇒ skip relations *and* skip
     *  composite scalar fields it omits; the named field ids are kept. */
    @Test
    fun directSelect_skipsUnprojectedComposites() {
        val projection = captureProjection(selectOp(Field.NAME), remaining = emptyList())

        assertEquals(Projection(keepFields = setOf(Field.NAME), skipUnprojectedComposites = true), projection)
    }

    /** A `select` downstream of an intervening op (e.g. `searchAll`) ⇒ skip
     *  relations only; keep all composite scalar fields (predicate may read them). */
    @Test
    fun downstreamSelect_skipsRelationsOnly() {
        val nonSelectProtocol = fieldRef(Field.VALUE) // stand-in for searchAll/filter
        val projection = captureProjection(nonSelectProtocol, remaining = listOf(selectOp(Field.NAME)))

        assertEquals(Projection(keepFields = emptySet(), skipUnprojectedComposites = false), projection)
        assertTrue(projection!!.keepFields.isEmpty())
    }

    /** No `select` consuming the array ⇒ no projection ⇒ full load (unchanged). */
    @Test
    fun noDownstreamSelect_returnsNull() {
        assertNull(captureProjection(fieldRef(Field.VALUE), remaining = listOf(fieldRef(Field.NAME))))
    }

    /** A `relations(rel) { fields(NAME) }` nested block ⇒ a child [Projection] keyed by the
     *  relation ref, carrying the nested selectors (core-nested-projection.md Slice 2.1). */
    @Test
    fun nestedSelect_buildsChildProjection() {
        val selectOp = OperationDto(
            params = mapOf(
                Select.Params.SELECTORS to TListDto(
                    listOf(
                        Selector.PropertySelector.create<SyntheticTestData>(
                            context = testProxy.tContext,
                            propertyReference = propertyRef("test/rel"),
                            nested = TListDto<Selector<*>>(
                                listOf(
                                    Selector.FieldSelector.create<SyntheticTestData>(
                                        testProxy.tContext,
                                        fieldReference = fieldRef(Field.NAME),
                                    )
                                )
                            ),
                        )
                    )
                )
            ),
            schema = Select.Schema,
        )

        val projection = captureProjection(selectOp, remaining = emptyList())

        assertEquals(
            Projection(
                keepFields = emptySet(),
                skipUnprojectedComposites = true,
                children = mapOf(
                    "test/rel" to Projection(
                        keepFields = setOf(Field.NAME),
                        skipUnprojectedComposites = true,
                    ),
                ),
            ),
            projection,
        )
    }
}
