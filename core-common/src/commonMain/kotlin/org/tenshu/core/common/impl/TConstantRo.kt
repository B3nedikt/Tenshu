package org.tenshu.core.common.impl

import org.tenshu.core.TConstant
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.ConstantSchema

/**
 * Chain-recording request object for a **constant-valued** property, so one can be referenced
 * inside a predicate — `.filter<File> { category equalTo FileCategory.Image }`.
 *
 * The twin of [TStringRo] / `UnixInstantRo` for the third value shape. Without it, a chain
 * accessor that appends a constant property's reference dies at *build* time with
 * "No request object factory registered for type: …", because `SchemaRegistry` has no way to
 * materialise the placeholder the chain hands the predicate — the constant's own registration
 * carries a `dtoFactory` (for a value crossing the wire) and nothing for a value being *described*.
 *
 * ⚠ A placeholder, never a value: [schema] is the *property's* type schema (`FileCategory.Schema`,
 * not `FileCategory.Image.schema`), because at chain-build time no row has been read and there is
 * nothing to be equal to yet. The comparison itself happens proxy-side in `equalToLogic`, which
 * compares two resolved `TConstant`s by id.
 *
 * Register it per constant type, capturing that type's schema:
 * ```
 * constant(
 *     interfaceClass = FileCategory::class,
 *     dataSchema = FileCategory.Schema,
 *     dtoFactory = { … },
 *     roFactory = { chain, context -> TConstantRo(chain, context, FileCategory.Schema) },
 * )
 * ```
 */
open class TConstantRo(
    override val requestChain: RequestChain,
    override val context: TContext,
    override val schema: ConstantSchema,
) : TConstant, RequestObject
