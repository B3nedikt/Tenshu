package org.tenshu.routine.proxy

import kotlin.test.Test
import kotlin.test.assertTrue
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.core.trait.isPunctual
import org.tenshu.routine.EvaluateDueRoutines

/**
 * The two traits the routines wake-up must carry, asserted where they are
 * declared — `:sync-service-proxy` cannot see this module (routines depend on
 * sync, not the reverse), so this is the right home for them.
 */
class RoutinePunctualityTest {

    /**
     * ⭐ RE-D5. Without this the platform scheduler batches the wake into its
     * next convenient slot, and "turn the porch light on at sunset" quietly
     * becomes "sometime this evening".
     */
    @Test
    fun the_routine_wake_is_punctual() {
        assertTrue(EvaluateDueRoutines.Schema.isPunctual())
    }

    /**
     * ⚠ And it must stay headless-safe: `ScheduleImplementation` is fail-closed
     * and refuses to arm anything without the trait, which would surface as a
     * confusing runtime refusal rather than a compile error.
     */
    @Test
    fun the_routine_wake_is_still_headless_safe() {
        assertTrue(EvaluateDueRoutines.Schema.isHeadlessSafe())
    }
}
