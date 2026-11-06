package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

/**
 * The substring after the last occurrence of [separator] — a pure, synchronous
 * [TString] → [TString] transform (`"/a/b/c.txt".substringAfterLast("/")` →
 * `"c.txt"`). If the separator is absent the whole string is returned, matching
 * Kotlin's `String.substringAfterLast`.
 *
 * Dual-dispatches like [contains]/[equalTo]: against a recording [RequestObject]
 * it appends a serialisable `SubstringAfterLast` op to the chain (resolved on the
 * proxy via [substringAfterLastLogic]); against real in-memory [Data] it evaluates
 * directly. It carries **no** IO/terminal/stream traits, so it composes mid-chain
 * (e.g. a derived-name `dynamicProperty` template `path.substringAfterLast("/")`,
 * then a `contains` predicate on the result) and resolves synchronously in a scan.
 */
fun TString.substringAfterLast(separator: String): TString {
    return if (this is RequestObject) {
        findOperation<SubstringAfterLast>(SubstringAfterLast.ID).substringAfterLast(separator)
    } else {
        substringAfterLastLogic(this, separator)
    }
}

interface SubstringAfterLast : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun substringAfterLast(separator: Any): TString

    companion object {
        const val ID = "tenshu/core/protocol/substringAfterLast"

        val Schema = operation(
            id = ID,
            receiver = TString.Schema.toType(),
            params = {
                composite(id = Params.SEPARATOR)
            }
        )
    }

    object Params {
        const val SEPARATOR: String = "${ID}/separator"
    }
}
