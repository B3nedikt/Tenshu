package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.dataFactory
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.collection.list.toTList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * ⚠⚠ **Pinned bytes, on every target** (`core-semantic-permissions.md` SP-D6). A signed manifest is
 * hashed over this encoding, so the same data must encode to the same bytes on the JVM, Android,
 * iOS and JS. The structural tests in [BinarySerializerTest] cannot show that: they build both sides
 * with the same `idToUuid` and the same integer encoding, so a platform whose MD5 or byte order
 * differed would pass them. These hex strings were taken once from the JVM and every other target
 * must reproduce them. ⚠ Never regenerate one to make it pass: a changed byte here is a changed
 * signature for every manifest that contains it.
 */
class CanonicalEncodingTest {

    private val context = createTestContext(createSyntheticTestRegistry())

    @Test
    fun anIdEncodesToTheSameSixteenBytesEverywhere() {
        assertEquals(ID_STRING, idToUuid("tenshu/core/string").toHex())
    }

    /** Every value kind that reaches the wire: string (non-ASCII), integer (negative), boolean, bytes. */
    @Test
    fun everyValueKindEncodesToPinnedBytes() {
        val list = context.dataFactory().createList<Data>(
            listOf(
                SyntheticTestData.Factory.create(context, "Grüße ✓", -2L),
                "".toTString(context),
                true.toTBoolean(context),
                (-1L).toTInteger(context),
                byteArrayOf(0, 127, -128, -1).toTByteArray(context),
            ),
        )
        assertEquals(VALUES, BinarySerializer().serialize(TResult.Success.create(context, list)).toHex())
    }

    /**
     * ⚠ The same vector is asserted on JS by `tenshu-sdk-js`'s `CanonicalEncodingJsTest`, because this
     * module compiles no JS tests (`core-common/build.gradle.kts`). Plain values only, so it needs no
     * test registry there.
     */
    @Test
    fun plainValuesEncodeToTheVectorJsAlsoPins() {
        val list = listOf<Data>(
            "Grüße ✓".toTString(),
            "".toTString(),
            true.toTBoolean(),
            (-1L).toTInteger(),
            byteArrayOf(0, 127, -128, -1).toTByteArray(),
        ).toTList()
        assertEquals(PLAIN_VALUES, BinarySerializer().serialize(list).toHex())
    }

    /** ⚠ Two encodings of one value by two serializers: no per-instance state may leak into the bytes. */
    @Test
    fun twoSerializersAndTwoRunsAgree() {
        val data = SyntheticTestData.Factory.create(context, "same", 7L)
        val first = BinarySerializer()
        val once = first.serialize(data)
        assertContentEquals(once, first.serialize(data))
        assertContentEquals(once, BinarySerializer().serialize(data))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val PLAIN_VALUES = "8dca8a3c1e263274b76ef3b2da7da972fcc23256897d33619c8fb4eea1046abc000000000000000b4772c3bcc39f6520e29c93fcc23256897d33619c8fb4eea1046abc00000000000000003fec5ed472b239c1b6f90ee329371a790000000000000001d209aa6a1d3133da824285ba1f07dbcfffffffffffffffff7b28d5ffce1031d8a90ac787d7385f4f0000000000000004007f80ff"
        const val ID_STRING = "fcc23256897d33619c8fb4eea1046abc"
        const val VALUES =
            "d3b20df24fd033e29fa606ab6237460d8dca8a3c1e263274b76ef3b2da7da972000000000000008f" +
                "665f6a6bf98f359da962f0a15c61aaee000000000000000b4772c3bcc39f6520e29c93fffffffffffffffe" +
                "fcc23256897d33619c8fb4eea1046abc0000000000000000" +
                "3fec5ed472b239c1b6f90ee329371a790000000000000001" +
                "d209aa6a1d3133da824285ba1f07dbcfffffffffffffffff" +
                "7b28d5ffce1031d8a90ac787d7385f4f0000000000000004007f80ff"
    }
}
