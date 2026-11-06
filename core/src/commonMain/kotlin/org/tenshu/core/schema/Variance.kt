package org.tenshu.core.schema

import org.tenshu.core.TConstant

sealed interface Variance : TConstant {

    object Invariant : Variance {
        override val schema: ConstantSchema = constant("${ID}/invariant")
    }

    object Covariant : Variance {
        override val schema: ConstantSchema = constant("${ID}/covariant")
    }

    object Contravariant : Variance {
        override val schema: ConstantSchema = constant("${ID}/contravariant")
    }

    companion object {
        const val ID = "tenshu/core/schema/variance"

        val Schema = constant(ID)
    }
}
