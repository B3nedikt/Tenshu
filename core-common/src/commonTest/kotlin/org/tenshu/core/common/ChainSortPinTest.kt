package org.tenshu.core.common

import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.sortBy
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.core.TContext
import org.tenshu.core.equalTo
import org.tenshu.core.toTString
import kotlin.test.Test

/**
 * **Static-typing pin for the `sortBy` chain step** (docs/proposals/core-chain-sort.md
 * §4) — mirrors [ChainEntityTypingPinTest]. Each `pin*` function declares the
 * expected return type and assigns the chain expression to it; a regression in
 * the chain-step return shape fails to compile, so the test binary never links.
 *
 * `sortBy` / `sortByDescending` are list→list, so the pinned shape is
 * `TList<E>` in, `TList<E>` out, for both field- and property-style selectors,
 * inferred and narrowed receivers, and composed after `searchAll`.
 */
class ChainSortPinTest {

    @Test
    fun typingsPinnedAtCompileTime() {
        // The pin is the set of `pin*` functions below — compile-time
        // assertions on the chain DSL's return shapes. This `@Test` exists so
        // the file surfaces in the test report; the assertion is "it compiled."
    }

    private fun stubCtx(): TContext = error("compile-time pin only")

    /** Inferred receiver, comparable-value (`TInteger`) selector. */
    @Suppress("unused")
    private fun pinSortByInferred(
        list: TList<SyntheticTestData>,
    ): TList<SyntheticTestData> =
        list.sortBy { value }

    /** Descending variant pins the same shape. */
    @Suppress("unused")
    private fun pinSortByDescendingInferred(
        list: TList<SyntheticTestData>,
    ): TList<SyntheticTestData> =
        list.sortByDescending { value }

    /** `TString` selector also types as `TList<E>`. */
    @Suppress("unused")
    private fun pinSortByStringKey(
        list: TList<SyntheticTestData>,
    ): TList<SyntheticTestData> =
        list.sortBy { name }

    /** Narrowing overload — single explicit type argument. */
    @Suppress("unused")
    private fun pinSortByNarrowed(
        list: TList<*>,
    ): TList<SyntheticTestData> =
        list.sortBy<SyntheticTestData> { value }

    /** Composed after `searchAll` — stays `TList<E>`. */
    @Suppress("unused")
    private fun pinSearchAllThenSortBy(
        list: TList<*>,
    ): TList<SyntheticTestData> =
        list.searchAll<SyntheticTestData> { name equalTo "x".toTString(stubCtx()) }
            .sortBy { value }
}
