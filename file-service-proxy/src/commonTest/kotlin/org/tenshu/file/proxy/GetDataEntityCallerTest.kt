package org.tenshu.file.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetData
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * `GetData` must accept an `Entity` caller, not only a bare `File` composite.
 *
 * ⚠⚠ **The defect this pins is a wrong accessor, not a missing field.** The implementation read the
 * caller's path with `getOptionalField(File.Field.PATH)` — but that field is **mandatory** on
 * `tenshu/file`, and `getOptionalField` refuses mandatory fields by contract: it answers `null`
 * (or throws *"…is mandatory…. Use getField() instead"*) rather than the value, which is
 * demonstrably present. The `?: error(...)` beneath then read that `null` as "no path in caller"
 * and failed the whole operation with `Field tenshu/file/path not found in caller`.
 *
 * ⚠ **Latent, not live — and that is worth stating precisely so nobody re-derives it.** No shipped
 * caller reaches this branch, because `ReferenceResolver.resolveDynamicProperty` unwraps an entity
 * through `fromEntity(data)` before invoking any operation, so a resolver only ever sees the plain
 * composite. (Measured: 107 `GetPreview` invocations across the whole system-test suite, zero with
 * an `Entity` caller.) The `is Entity<*>` branches dotted through `GetData` and `GetPreview` are
 * therefore unreachable by construction — but they exist, they read as supported, and the first
 * caller to invoke this op directly with an entity would have hit the failure above. One line and
 * a test is cheaper than that discovery.
 */
class GetDataEntityCallerTest {

    private val testProxy = createFileProxy()

    @Test
    fun anEntityCallerResolvesItsPathAndReturnsTheBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val entity = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(testProxy.tContext, "/photo.jpg"),
        ) {
            set(File.ContentId, "content-photo")
        }
        testProxy.executionContext.saveData(entity)
        testProxy.fileDatabase.writeRawByteArray("/photo.jpg", bytes)

        val data = testProxy.executionContext.execute<TByteArray?>(
            operationId = GetData.ID,
            caller = entity,
        )

        assertContentEquals(
            bytes, assertNotNull(data, "an Entity caller must resolve to its file's bytes").get(),
            "the path is on the entity — it just has to be read with the mandatory-field accessor",
        )
    }

    @Test
    fun aBareCompositeCallerStillResolves() {
        // The shipped shape, unchanged: every production caller arrives as a plain composite.
        val bytes = byteArrayOf(9, 9)
        val file = File.create(testProxy.tContext, "/plain.jpg")
        testProxy.fileDatabase.writeRawByteArray("/plain.jpg", bytes)

        val data = testProxy.executionContext.execute<TByteArray?>(
            operationId = GetData.ID,
            caller = file,
        )

        assertContentEquals(bytes, assertNotNull(data).get())
    }
}
