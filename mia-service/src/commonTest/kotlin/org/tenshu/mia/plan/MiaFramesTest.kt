package org.tenshu.mia.plan

import org.tenshu.calendar.SaveEvent
import org.tenshu.core.collection.list.toList
import org.tenshu.core.get
import org.tenshu.core.schema.OperationSchema
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.routine.RunRoutineNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ `MiaFrames` is keyed by param id because the schema has no semantic param types yet (§25,
 * CL-D3). That makes it a second copy of each op's param list, so this pins the copy against the
 * op's own schema: every frame param exists on the op, and every required op param is in the
 * frame. A renamed or added op param fails here, not on a confirmed write.
 */
class MiaFramesTest {

    private val schemas: Map<String, OperationSchema> = mapOf(
        SaveEvent.ID to SaveEvent.Schema,
        IssueDeviceCommand.ID to IssueDeviceCommand.Schema,
        RunRoutineNow.ID to RunRoutineNow.Schema,
    )

    @Test
    fun everyFrameMatchesItsOpSchema() {
        assertEquals(schemas.keys, MiaFrames.ALL.map { it.op }.toSet(), "a frame without a pinned schema")

        MiaFrames.ALL.forEach { frame ->
            val params = schemas.getValue(frame.op).params.toList()
            val ids = params.map { it.id.get() }.toSet()
            val required = params.filterNot { it.isOptional.get() }.map { it.id.get() }.toSet()
            val framed = frame.params.map { it.id }.toSet()

            assertTrue(ids.containsAll(framed), "${frame.op}: frame names params the op lacks: ${framed - ids}")
            assertTrue(framed.containsAll(required), "${frame.op}: required op params missing from the frame: ${required - framed}")
        }
    }

    @Test
    fun onlyAnUtteranceParamCarriesASlotType() {
        MiaFrames.ALL.flatMap { it.params }.forEach { param ->
            if (param.slot != null) {
                assertEquals(MiaFrames.Fill.UTTERANCE, param.fill, "${param.id}: a slot type on a param nobody is asked for")
            }
        }
    }
}
