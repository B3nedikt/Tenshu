package app.tenshu.control.data

import org.tenshu.core.common.util.HlcStamp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A relay status is shown only against the removal it answered — never an older one of the same device. */
class SameRemovalTest {

    private val first = HlcStamp(1_000L, 0L, "owner-device").encode()
    private val later = HlcStamp(9_000L, 0L, "other-device").encode()

    @Test
    fun theSameStamp_isTheSameRemoval() = assertTrue(sameRemoval(first, first))

    @Test
    fun anOlderRowOfTheSameDevice_isNotShownAgainstANewerRemoval() = assertFalse(sameRemoval(later, first))

    @Test
    fun anUnreadableStamp_neverMatches() = assertFalse(sameRemoval("", ""))
}
