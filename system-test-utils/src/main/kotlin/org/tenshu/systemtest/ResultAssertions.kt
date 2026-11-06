package org.tenshu.systemtest

import org.tenshu.core.Data
import org.tenshu.core.TResult

/**
 * The value of a successful [TResult], or a failure that says what went wrong.
 *
 * The idiom this replaces — `(result as TResult.Success).value!!` — is spread across the system
 * tests, and it **destroys the error it lands on**: an op that returns `TResult.Failure` surfaces
 * as `ClassCastException: ResultFailureDto cannot be cast to TResult$Success`, naming neither the
 * call nor its cause. Two of the three flakes caught in the 2026-08-02 gate hunt reported exactly
 * that, from two different tests, and both were the *same* underlying failure — which was only
 * identifiable because a third test happened to assert with a message and printed
 * `401 Unauthorized` instead (docs/tech-debt.md).
 *
 * A cast is the wrong tool here for a reason worth stating: it encodes "this cannot fail" about a
 * relay round-trip that demonstrably can, in a suite whose whole open problem is intermittent
 * failure under load. Failing loudly with the error attached costs one call and turns an
 * unreproducible flake into a diagnosis from the run that produced it.
 */
fun <T : Data?> TResult<T>.valueOrFail(what: String): T {
    if (this is TResult.Failure<*>) {
        error("$what failed: ${this.error}")
    }
    @Suppress("UNCHECKED_CAST")
    return (this as TResult.Success<T>).value as T
}
