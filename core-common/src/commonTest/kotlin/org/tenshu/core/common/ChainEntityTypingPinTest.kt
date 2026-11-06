package org.tenshu.core.common

import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.toTString
import kotlin.test.Test

/**
 * **Static-typing pin test for the chain DSL** — locks in the return
 * shapes from `core-chain-entity-typing.md` §2.1 + §2.2. Each `pin*` function declares the
 * expected return type and assigns the chain expression to it; if any
 * step's typing regresses, this file fails to compile — the test
 * never runs in the regressed state because the test binary doesn't
 * link.
 *
 * Why a pin test: each chain step's static return type is part of the
 * consumer contract (see `TenshuFilesRepository.getRemoteFile` —
 * dropping the `as? Entity<*>` narrowing relies on
 * `searchAll<File>.select { … }` typing as `TList<Entity<File>>`).
 * Any reshape — even a well-intentioned one — that silently loosens
 * a return type into `Data` / `Any` / `*`-projected would compile
 * without warning at the chain DSL level, then quietly re-introduce
 * call-site casts in every consumer. The pin functions below catch
 * that at the chain DSL's own compile pass, before downstream
 * modules get a chance to silently absorb the regression.
 *
 * The pin functions are `private` + `@Suppress("unused")` because
 * they exist purely to constrain types — they are never called. The
 * single `@Test` method is a marker so the pin shows up in the test
 * report alongside runtime tests.
 */
class ChainEntityTypingPinTest {

    @Test
    fun typingsPinnedAtCompileTime() {
        // The pin is the set of `pin*` functions below — compile-time
        // assertions on the chain DSL's return shapes. This `@Test`
        // method exists so the file surfaces in the test report; the
        // assertion is "the test class compiled at all."
    }

    /** Sentinel value used as a parameter source. Never invoked at
     *  runtime — the pin functions are private + unused. */
    private fun stubCtx(): TContext = error("compile-time pin only")

    // ─── §2.1 — `TList.search` returns `Maybe<Entity<E>>`, not `Maybe<E>` ────

    /** Default overload — type inferred from receiver's element type.
     *  Pins `TList<E>.search { predicate }: Maybe<Entity<E>>`. */
    @Suppress("unused")
    private fun pinSearchDefault(
        list: TList<SyntheticTestData>,
    ): Maybe<Entity<SyntheticTestData>> =
        list.search { name equalTo "x".toTString(stubCtx()) }

    /** Narrowing overload — explicit `<T>` selects a subtype. Pins
     *  `TList<*>.search<T> { predicate }: Maybe<Entity<T>>`. */
    @Suppress("unused")
    private fun pinSearchNarrowing(
        list: TList<*>,
    ): Maybe<Entity<SyntheticTestData>> =
        list.search<SyntheticTestData> { name equalTo "x".toTString(stubCtx()) }

    /** `searchAll` stays list-shaped (not retyped to `TList<Entity<E>>`
     *  per the proposal — the listing path drives bulk projections via
     *  `select` which IS retyped). Pins
     *  `TList<*>.searchAll<T> { predicate }: TList<T>`. */
    @Suppress("unused")
    private fun pinSearchAll(
        list: TList<*>,
    ): TList<SyntheticTestData> =
        list.searchAll<SyntheticTestData> { name equalTo "x".toTString(stubCtx()) }

    // ─── §2.2 — `select` overloads on TList / Maybe / Entity ────

    /** Pins `TList<R>.select { … }: TList<Entity<R>>` (the original
     *  shape — receiver-list, returns list of projected entities). */
    @Suppress("unused")
    private fun pinListSelect(
        list: TList<SyntheticTestData>,
    ): TList<Entity<SyntheticTestData>> =
        list.select { fields(SyntheticTestData.Companion.Field.NAME) }

    /** Pins `Maybe<T>.select { … }: Maybe<T>` (preserves the wrapper
     *  shape; empty-as-value propagates). */
    @Suppress("unused")
    private fun pinMaybeSelect(
        maybe: Maybe<SyntheticTestData>,
    ): Maybe<SyntheticTestData> =
        maybe.select { fields(SyntheticTestData.Companion.Field.NAME) }

    /** Pins `Entity<T>.select { … }: Entity<T>` (throw-on-empty path,
     *  reached via `.ifEmptyError().select(…)`). */
    @Suppress("unused")
    private fun pinEntitySelect(
        entity: Entity<SyntheticTestData>,
    ): Entity<SyntheticTestData> =
        entity.select { fields(SyntheticTestData.Companion.Field.NAME) }

    // ─── §2.2 — composed chain ────

    /** Composed pin: `searchAll → select → … ` types as
     *  `TList<Entity<…>>`. Matches `TenshuFilesRepository.getRemoteFile`
     *  / `getPreview`'s pre-`.flow()` shape; if this regresses, the
     *  consumer's `firstOrNull()` → `Entity<File>?` flow falls back
     *  to needing an `as? Entity<*>` cast. */
    @Suppress("unused")
    private fun pinSearchAllThenSelect(
        list: TList<*>,
    ): TList<Entity<SyntheticTestData>> =
        list.searchAll<SyntheticTestData> { name equalTo "x".toTString(stubCtx()) }
            .select { fields(SyntheticTestData.Companion.Field.NAME) }

    /** Composed pin for the Maybe path: `search → select` preserves
     *  `Maybe<Entity<…>>` end-to-end. */
    @Suppress("unused")
    private fun pinSearchThenMaybeSelect(
        list: TList<SyntheticTestData>,
    ): Maybe<Entity<SyntheticTestData>> =
        list.search { name equalTo "x".toTString(stubCtx()) }
            .select { fields(SyntheticTestData.Companion.Field.NAME) }
}
