package org.tenshu.inference.proxy

/**
 * This value rounded to IEEE-754 single precision.
 *
 * A no-op on the JVM, Android and Kotlin/Native, where a `Float` already is one. On Kotlin/JS a
 * `Float` is a 64-bit double in memory and only a `FloatArray` (a `Float32Array`) or a bit
 * conversion rounds it, so arithmetic keeps precision the other targets drop, and a value read back
 * from float32 bytes is not `==` to the one written.
 *
 * ⚠ `toRawBits`, not `toBits`: the raw form keeps a NaN's payload instead of canonicalising it, so
 * the round trip changes no bit on any target.
 */
internal fun Float.asFloat32(): Float = Float.fromBits(toRawBits())
