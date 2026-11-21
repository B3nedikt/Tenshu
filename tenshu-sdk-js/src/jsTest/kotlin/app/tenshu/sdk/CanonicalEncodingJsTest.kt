package app.tenshu.sdk

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ The JS half of `:core-common`'s `CanonicalEncodingTest` (`core-semantic-permissions.md`
 * SP-D6): `:core-common` compiles no JS tests, so its pinned vectors are asserted here. ⚠ The two
 * constants must equal that test's `ID_STRING` and `PLAIN_VALUES` byte for byte; a manifest's
 * signature is over these bytes, so a JS runtime that encodes differently could not verify one.
 */
class CanonicalEncodingJsTest {

    @Test
    fun anIdEncodesToTheJvmBytes() {
        assertEquals(ID_STRING, idToUuid("tenshu/core/string").toHex())
    }

    @Test
    fun plainValuesEncodeToTheJvmBytes() {
        val list = listOf<Data>(
            "Grüße ✓".toTString(),
            "".toTString(),
            true.toTBoolean(),
            (-1L).toTInteger(),
            byteArrayOf(0, 127, -128, -1).toTByteArray(),
        ).toTList()
        assertEquals(PLAIN_VALUES, BinarySerializer().serialize(list).toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val ID_STRING = "fcc23256897d33619c8fb4eea1046abc"
        const val PLAIN_VALUES = "8dca8a3c1e263274b76ef3b2da7da972fcc23256897d33619c8fb4eea1046abc000000000000000b4772c3bcc39f6520e29c93fcc23256897d33619c8fb4eea1046abc00000000000000003fec5ed472b239c1b6f90ee329371a790000000000000001d209aa6a1d3133da824285ba1f07dbcfffffffffffffffff7b28d5ffce1031d8a90ac787d7385f4f0000000000000004007f80ff"
    }
}
