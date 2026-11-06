package org.tenshu.core.common.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The single-instance guard Control takes at startup. The cross-process cases run a real second
 * JVM, because the 2026-11-04 defect was two processes and an in-process check alone would pass
 * on a lock that never reaches the OS.
 */
class SingleInstanceLockTest {
    private val dir: File = Files.createTempDirectory("single-instance-lock").toFile()
    private val lockFile = File(dir, "control.instance.lock")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aSecondProcessIsRefusedWhileTheFirstHoldsTheLock() {
        val first = assertIs<SingleInstanceLock.Outcome.Acquired>(SingleInstanceLock.tryAcquire(lockFile))
        try {
            val child = runProbe(ProbeMode.TRY)
            assertEquals(ProbeExit.HELD, child.exit, "a second process must be refused: ${child.output}")
            // The refused copy can name the running one.
            assertTrue("pid ${ProcessHandle.current().pid()}" in child.output, child.output)
        } finally {
            first.lock.release()
        }
        assertEquals(ProbeExit.ACQUIRED, runProbe(ProbeMode.TRY).exit, "free once the first releases")
    }

    @Test
    fun aSecondHolderInTheSameProcessIsRefused() {
        val first = assertIs<SingleInstanceLock.Outcome.Acquired>(SingleInstanceLock.tryAcquire(lockFile))
        try {
            assertIs<SingleInstanceLock.Outcome.HeldElsewhere>(SingleInstanceLock.tryAcquire(lockFile))
        } finally {
            first.lock.release()
        }
        val again = assertIs<SingleInstanceLock.Outcome.Acquired>(SingleInstanceLock.tryAcquire(lockFile))
        again.lock.release()
    }

    @Test
    fun theLockDiesWithItsProcessAndTheLeftoverFileIsInert() {
        // A holder that is killed, not quit: no release, no cleanup, the file stays behind.
        val holder = startProbe(ProbeMode.HOLD)
        try {
            val ready = holder.inputStream.bufferedReader().readLine()
            assertEquals("HOLDING", ready, "the child must report it holds the lock")
            assertIs<SingleInstanceLock.Outcome.HeldElsewhere>(SingleInstanceLock.tryAcquire(lockFile))
        } finally {
            holder.destroyForcibly()
            holder.waitFor(30, TimeUnit.SECONDS)
        }
        assertTrue(lockFile.exists(), "the lock file is never deleted")
        val after = assertIs<SingleInstanceLock.Outcome.Acquired>(SingleInstanceLock.tryAcquire(lockFile))
        after.lock.release()
    }

    @Test
    fun anUnopenableLockFileIsUnavailableNotHeld() {
        // A directory where the file should be: opening it fails, which says nothing about a holder.
        lockFile.mkdirs()
        assertIs<SingleInstanceLock.Outcome.Unavailable>(SingleInstanceLock.tryAcquire(lockFile))
    }

    private class ProbeResult(val exit: Int, val output: String)

    private fun startProbe(mode: ProbeMode): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            SingleInstanceLockProbe::class.java.name,
            mode.name,
            lockFile.absolutePath,
        ).redirectErrorStream(true).start()
    }

    private fun runProbe(mode: ProbeMode): ProbeResult {
        val process = startProbe(mode)
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("probe did not exit: $output")
        }
        return ProbeResult(process.exitValue(), output)
    }
}

internal enum class ProbeMode { TRY, HOLD }

internal object ProbeExit {
    const val ACQUIRED = 0
    const val HELD = 3
    const val UNAVAILABLE = 4
}

/** The other process in [SingleInstanceLockTest]: a stand-in for a second Control starting. */
internal object SingleInstanceLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = ProbeMode.valueOf(args[0])
        when (val outcome = SingleInstanceLock.tryAcquire(File(args[1]))) {
            is SingleInstanceLock.Outcome.Acquired -> {
                if (mode == ProbeMode.HOLD) {
                    println("HOLDING")
                    System.out.flush()
                    Thread.sleep(Long.MAX_VALUE)
                }
                outcome.lock.release()
                kotlin.system.exitProcess(ProbeExit.ACQUIRED)
            }
            is SingleInstanceLock.Outcome.HeldElsewhere -> {
                println("held by ${outcome.holder}")
                kotlin.system.exitProcess(ProbeExit.HELD)
            }
            is SingleInstanceLock.Outcome.Unavailable -> {
                println("unavailable: ${outcome.cause}")
                kotlin.system.exitProcess(ProbeExit.UNAVAILABLE)
            }
        }
    }
}
