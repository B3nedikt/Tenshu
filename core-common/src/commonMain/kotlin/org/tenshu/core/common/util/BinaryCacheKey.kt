package org.tenshu.core.common.util

/**
 * Deterministic, injective encoding of arbitrary binary bytes into a [String]
 * cache key.
 *
 * The Control ⇄ Drive Binder side channels (`SharedMemoryCache` /
 * `ParcelFileDescriptorCache`) are keyed by `String`, but the id they key on is
 * arbitrary binary — e.g. `InMemoryByteArrayDto.uniqueId = md5Hash(content)`
 * (16 raw bytes) or, on the SharedMemory-passthrough path, raw content bytes.
 *
 * Using `String(bytes)` (the platform charset, UTF-8 on Android) is **lossy and
 * NOT injective**: invalid UTF-8 byte sequences decode to the replacement
 * character `U+FFFD`, so distinct ids collapse to the same `String`. A colliding
 * `put` then closes the displaced `SharedMemory` / `ParcelFileDescriptor` handle
 * while a live DTO still references it, and the next lazy `.get()` crashes the
 * Drive process with `"... not found in cache"` (surfaced by the §5d Android
 * smoke during a many-DB-change background folder upload).
 *
 * Hex is the simplest obviously-injective encoding (byte → exactly two stable
 * hex chars, no charset involved). This is the **single source of truth** — both
 * the put side (`BinderTaskTransport`) and the get side
 * (`SharedMemoryByteArrayDto` / `ParcelFileDescriptorFileByteArrayDto`) MUST
 * derive their key through this function so they can never drift.
 */
fun ByteArray.toBinaryCacheKey(): String = toHexString()
