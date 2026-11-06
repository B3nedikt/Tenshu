package org.tenshu.file.client

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RoProvider
import org.tenshu.core.schema.Type
import org.tenshu.file.client.impl.GetPreviewRo
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The one route a client has to D-17** (`drive-xxlarge-thumbnail-rung.md` §4.2, X-3).
 * `GetPreview.Params.PREFER_ORIGINAL` is read by all three proxy actuals out of the operation's
 * parameter map, and `File.ViewerPreview` is the only thing that puts it there — through this `Ro`.
 * If it stopped being written, nothing would fail: the actuals would read `false`, the ladder would
 * stretch the top rung to the request, and the device holding the original would quietly start
 * drawing its own photograph upscaled. That is the X-1 review's finding 1, and this is the assertion
 * standing between it and a return.
 *
 * ⚠ The absent case matters as much as the present one: absence means today's ladder, and writing a
 * `false` instead would put a new field on the wire for every `File.Preview` caller in the system.
 */
class GetPreviewRoPrefersOriginalTest {

    @Test
    fun theFlagIsOnTheWireWhenTheViewerAsksForIt() {
        val params = paramsOf(preferOriginal = true)
        assertTrue(
            GetPreview.Params.PREFER_ORIGINAL in params,
            "the viewer's read must carry the flag: ${params.keys}",
        )
        assertEquals(true, (params[GetPreview.Params.PREFER_ORIGINAL] as? TBoolean)?.get())
    }

    @Test
    fun andIsAbsentEntirelyOtherwise() {
        val params = paramsOf(preferOriginal = false)
        assertFalse(
            GetPreview.Params.PREFER_ORIGINAL in params,
            "absence is what every existing caller sends, and it means today's ladder",
        )
        // The request itself is unchanged — same three parameters `File.Preview` has always sent.
        assertEquals(
            setOf(GetPreview.Params.FILE, GetPreview.Params.WIDTH, GetPreview.Params.HEIGHT),
            params.keys,
        )
    }

    private fun paramsOf(preferOriginal: Boolean): Map<String, Data> {
        val captured = mutableListOf<RequestChain>()
        val roProvider = object : RoProvider {
            override fun <T : Data> createRequestObject(
                type: Type,
                requestChain: RequestChain,
                context: TContext
            ): T {
                captured += requestChain
                // ⚠ The chain is what this test reads; the value is never used. It must still be a
                // real `TByteArray`, because that is what `getPreview` is typed to return.
                @Suppress("UNCHECKED_CAST")
                return TByteArrayDto(ByteArray(0)) as T
            }
        }
        val context = createTestContext(
            schemaRegistry = createFileTestRegistry(),
            additionalComponents = mapOf(RoProvider.COMPONENT_ID to roProvider),
        )
        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> =
                error("the chain is inspected, never executed")
        }
        val ro = GetPreviewRo(TaskRequestChain(taskExecutor, context), context)

        ro.getPreview(
            file = File.create(context, "/Backup/Phone/Camera/p1.jpg"),
            width = 3_072,
            height = 3_072,
            preferOriginal = preferOriginal,
        )

        val operation = captured.single().getCallstack().last() as Operation
        return operation.params
    }
}
