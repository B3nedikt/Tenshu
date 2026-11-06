package org.tenshu.core.common.parser

interface Token {

    val size: Int

    val value: Any

    data class ReferenceToken(override val value: String) : Token {
        override val size: Int = 16
    }

    data class StringToken(override val value: String) : Token {
        override val size: Int = value.encodeToByteArray().size
    }

    data class IntegerToken(override val value: Long) : Token {
        override val size: Int = 8
    }

    @Suppress("ArrayInDataClass")
    data class ByteArrayToken(override val value: ByteArray) : Token {
        override val size: Int = value.size
    }
}