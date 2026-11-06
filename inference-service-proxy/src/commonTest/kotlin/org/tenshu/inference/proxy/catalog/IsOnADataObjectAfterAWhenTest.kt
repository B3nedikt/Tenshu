package org.tenshu.inference.proxy.catalog

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ A Kotlin/Native trap, pinned (`ios-on-device-inference.md` §14.18.10; Kotlin 2.4.10).
 *
 * `BackgroundModelDownloader.readForAction` first read
 * `changing { lastReadFailed = journal is Journal.Unreadable }`, with `journal` a `val` assigned
 * from a `when` **in the same function**, and `changing` an inline function whose body is
 * `synchronized(lock) { try { change() } finally { … } }`. On the iOS simulator that test
 * answered **true for every value**, the plain class included; on the JVM it is right.
 *
 * [KotlinNativeTypeTestShapes.originalShape] is that function, outside the downloader. Seven
 * neighbours of it are right on both targets (the cases below): the same test outside the
 * lambda, with `==`, made before the lambda, under a bare `synchronized`, in an inline lambda
 * with no `try`/`finally`, returned from the lambda, and with the value made by another
 * function. ⚠ So the fault needs all of: the `when` in the same function, `is` on the
 * `data object`, and the assignment inside that inline `try`/`finally` lambda. The cause in the
 * compiler was not looked for.
 *
 * **The rule that follows:** decide in the branch that makes the value, or test it before
 * the lambda. What the original shape answers on each target is asserted per target
 * (`…OnTheJvm`, `…OnNative`): the Native one fails the day the compiler is fixed.
 */
class IsOnADataObjectAfterAWhenTest {

    private val shapes = KotlinNativeTypeTestShapes

    @Test fun outsideAnyLambda() = assertEquals(shapes.expected, shapes.each { outsideAnyLambda() })
    @Test fun withEquals() = assertEquals(shapes.expected, shapes.each { withEquals() })
    @Test fun testedBeforeTheLambda() = assertEquals(shapes.expected, shapes.each { testedBeforeTheLambda() })
    @Test fun underABareSynchronized() = assertEquals(shapes.expected, shapes.each { underABareSynchronized() })
    @Test fun inAnInlineLambdaWithNoTryFinally() = assertEquals(shapes.expected, shapes.each { inAnInlineLambdaWithNoTryFinally() })
    @Test fun returnedFromTheLambda() = assertEquals(shapes.expected, shapes.each { returnedFromTheLambda() })
    @Test fun notIsTheClassWithTheValueMadeElsewhere() = assertEquals(shapes.expected, shapes.each { notIsRead() })
}

internal object KotlinNativeTypeTestShapes {
    sealed interface Source {
        data object Absent : Source
        class Held(val items: List<String>) : Source
        data object Unreadable : Source
        data object Undecodable : Source
    }

    sealed interface Journal {
        class Read(val items: List<String>) : Journal
        data object Unreadable : Journal
    }

    class Reader(private val next: () -> Source) {
        private val ticks = SynchronizedObject()
        var lastReadFailed: Boolean = false
        var published: Int = 0

        private inline fun <T> changing(change: () -> T): T = synchronized(ticks) {
            try {
                change()
            } finally {
                published += 1
            }
        }

        fun read(): Journal {
            val journal: Journal = when (val read: Source = next()) {
                Source.Unreadable -> Journal.Unreadable
                Source.Absent -> Journal.Read(emptyList())
                Source.Undecodable -> Journal.Read(emptyList())
                is Source.Held -> Journal.Read(read.items)
            }
            changing { lastReadFailed = journal is Journal.Unreadable }
            return journal
        }

        private fun made(): Journal = when (val read: Source = next()) {
            Source.Unreadable -> Journal.Unreadable
            Source.Absent -> Journal.Read(emptyList())
            Source.Undecodable -> Journal.Read(emptyList())
            is Source.Held -> Journal.Read(read.items)
        }

        fun outsideAnyLambda(): Boolean {
            val journal: Journal = made()
            return journal is Journal.Unreadable
        }

        fun withEquals(): Boolean {
            val journal: Journal = made()
            changing { lastReadFailed = journal == Journal.Unreadable }
            return lastReadFailed
        }

        fun testedBeforeTheLambda(): Boolean {
            val journal: Journal = made()
            val failed: Boolean = journal is Journal.Unreadable
            changing { lastReadFailed = failed }
            return lastReadFailed
        }

        fun underABareSynchronized(): Boolean {
            val journal: Journal = made()
            synchronized(ticks) { lastReadFailed = journal is Journal.Unreadable }
            return lastReadFailed
        }

        fun inAnInlineLambdaWithNoTryFinally(): Boolean {
            val journal: Journal = made()
            run { lastReadFailed = journal is Journal.Unreadable }
            return lastReadFailed
        }

        fun returnedFromTheLambda(): Boolean {
            val journal: Journal = made()
            return changing { journal is Journal.Unreadable }
        }

        fun notIsRead(): Boolean {
            val journal: Journal = made()
            changing { lastReadFailed = journal !is Journal.Read }
            return lastReadFailed
        }
    }

    val answers = listOf(Source.Absent, Source.Held(listOf("a")), Source.Unreadable, Source.Undecodable, Source.Held(emptyList()))
    val expected = listOf(false, false, true, false, false)

    fun each(ask: Reader.() -> Boolean): List<Boolean> = answers.map { answer -> Reader { answer }.ask() }

    /** The shape that went wrong: `read()`, then what it left in `lastReadFailed`. */
    fun originalShape(): List<Boolean> = answers.map { answer -> Reader { answer }.also { it.read() }.lastReadFailed }
}
