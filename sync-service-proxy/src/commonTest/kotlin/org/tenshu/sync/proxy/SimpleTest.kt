package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleTest {
    @Test
    fun testSimple() = runTest {
        println("SIMPLE TEST START")
        assertTrue(true)
        println("SIMPLE TEST END")
    }
}
