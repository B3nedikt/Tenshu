package org.tenshu.core.common.util

import org.tenshu.core.common.impl.UnixInstantDto
import kotlin.test.Test
import kotlin.test.assertTrue

class TimestampFormattingTest {

    @Test
    fun `formatTimestamp accepts a Tenshu UnixInstant without manual conversion`() {
        val fixedMs = 1_704_067_200_000L  // 2024-01-01T00:00:00Z
        val instant = UnixInstantDto(fixedMs)
        val formatted = TimestampUtil.formatTimestamp(instant)
        assertTrue(formatted.isNotEmpty())
        // Don't assert exact formatting (locale-dependent); just round-trip the unit.
    }
}
