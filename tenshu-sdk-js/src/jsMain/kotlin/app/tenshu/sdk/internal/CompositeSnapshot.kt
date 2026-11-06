package app.tenshu.sdk.internal

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.fields
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.Reference
import org.tenshu.core.time.UnixInstant

// Schema-driven flattener: walks `composite.fields` and produces a plain JS
// object whose keys are the last path segment of each field id (e.g. the field
// `tenshu/identity/device/deviceId` becomes `deviceId`) and whose values are
// JS-native primitives.
//
// Rationale: this is the "dynamic-typing" half of the bridge. Kotlin Composite
// interfaces have generic methods and reactive wrapper types (TString etc.)
// that resist @JsExport. This walker hides all of that — JS callers never see
// a raw Composite; they see flat objects already typed by the hand-written TS
// adapter in `web/sdk`. See docs/web/architecture.md §12.6 / §15.3 (schema
// walker pattern).
//
// Adding a new leaf Data type means adding a branch to `unwrap`. The `error`
// fallback is intentional — silent skips would let mismatches between the
// Kotlin schema and the JS surface go unnoticed.
internal fun Composite.toJs(): dynamic {
    val obj: dynamic = js("({})")
    fields.forEach { (id, data) ->
        val key = id.substringAfterLast('/')
        obj[key] = unwrap(data)
    }
    return obj
}

// Branch ordering matters: more specific types must come first.
//   UnixInstant : TInteger      → handle UnixInstant before TInteger
//   TList       : Composite     → handle TList     before Composite
//
// Parked: Phase 3.2 of `docs/adr/0005-composite-schema-typing.md` would
// substitute TypeVariable references using `schema.typeParams` when emitting TS
// types — but this walker is a runtime *value* flattener, not a TS-type emitter,
// so there's nothing to substitute today. Picks back up if/when a schema → `.d.ts`
// emitter lands (see `docs/tech-debt.md` §"Dukat Output Quality"). The Kotlin
// Composite branch below already handles TypeVariable as a value (emits
// `{name: "E"}`).
private fun unwrap(data: Data): dynamic = when (data) {
    is TString -> data.get()
    is TBoolean -> data.get()
    is TByteArray -> data.get()
    // UnixInstant goes out as epoch milliseconds (JS number). Matches
    // `new Date(ms)` semantics on the JS side. JS Long → Number is lossy
    // beyond 2^53; for epoch-ms values that's well past year 287396, fine.
    is UnixInstant -> data.toEpochMilliseconds().toDouble()
    // TInteger → JS number. Long → Double conversion loses precision beyond
    // 2^53 (~9 PB if interpreted as bytes); BigInt is the future fix when
    // a domain field actually needs values that large.
    is TInteger -> data.get().toDouble()
    // TList → JS Array of unwrapped elements. SimpleTList implements List<T>
    // by delegation, so toList() is a free cast for the materialised case.
    is TList<*> -> data.toList().map { unwrap(it) }.toTypedArray()
    // ArrayDatabaseReference: unresolved DB-backed list (e.g. a Folder's
    // `files` field as loaded from the DB before something materialises it).
    // The JS surface is flat-per-level — callers navigate nested collections
    // via per-level operations like `FileSystem.list("/path")` rather than
    // inlining child collections into the parent. Emit an empty array so
    // the field shape is preserved.
    is ArrayDatabaseReference -> emptyArray<dynamic>()
    // A type/entity Reference (e.g. the runtime type tag projected by a
    // `traverse().select{…}`, as in FileSystem.recentFiles) is a string-valued
    // TConstant leaf. Emit its string; JS callers key off the schema fields
    // they care about and ignore the rest. Without this the Home/recent surface
    // throws "unsupported Data leaf CompositeTypeReferenceDto".
    is Reference -> data.get()
    is Composite -> data.toJs()
    else -> error("compositeToJs: unsupported Data leaf ${data::class.simpleName}")
}
