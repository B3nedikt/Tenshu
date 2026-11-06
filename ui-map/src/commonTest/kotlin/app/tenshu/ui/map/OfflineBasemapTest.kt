package app.tenshu.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schematic basemap's deterministic generator (docs/proposals/maps-app.md §6). The render
 * itself is visual (verified on device / desktop `run`), but its *determinism* — the same `seed`
 * always draws the same street layout, different seeds differ — is the load-bearing contract the
 * live MapLibre swap inherits, so it is unit-tested here.
 */
class OfflineBasemapTest {

    private fun sequence(seed: Int, n: Int = 12): List<Float> {
        val r = Lcg(seed)
        return List(n) { r.next() }
    }

    @Test
    fun sameSeed_isDeterministic() {
        assertEquals(sequence(7), sequence(7))
        assertEquals(sequence(42), sequence(42))
    }

    @Test
    fun differentSeeds_diverge() {
        assertTrue(sequence(7) != sequence(12))
    }

    @Test
    fun outputsAreUnitRange() {
        val r = Lcg(3)
        repeat(100) {
            val v = r.next()
            assertTrue(v >= 0f && v < 1f, "value out of [0,1): $v")
        }
    }

    @Test
    fun zeroSeed_doesNotStall() {
        // seed 0 → the LCG's `|| 1` guard must keep it advancing (not a constant stream).
        val r = Lcg(0)
        val a = r.next()
        val b = r.next()
        assertTrue(a != b)
    }
}
