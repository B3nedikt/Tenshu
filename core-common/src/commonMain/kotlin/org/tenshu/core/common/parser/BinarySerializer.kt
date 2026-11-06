package org.tenshu.core.common.parser

import kotlinx.io.bytestring.buildByteString
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.TValue
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.Type

class BinarySerializer(
    schemaProvider: SchemaProvider? = null
) {

    val serializer = LinearSerializer(schemaProvider)

    fun serialize(
        data: Data,
        interceptValueTokenization: ((TValue) -> TokenString?)? = null,
        isTopLevel: Boolean = true,
        expectedType: Type? = null
    ): ByteArray {
        return serializer
            .serialize(
                data = data,
                isTopLevel = isTopLevel,
                expectedType = expectedType,
                valueTokenizer = { value ->

                    val interceptedValue = interceptValueTokenization?.invoke(value)
                    if (interceptedValue != null) {
                        return@serialize interceptedValue
                    }

                    buildTokenString {
                        when (value) {
                            is Reference -> {
                                append(Token.ReferenceToken(value.get()))
                            }

                            is TByteArray -> {
                                append(Token.ByteArrayToken(value.get()))
                            }

                            is TString -> {
                                append(Token.StringToken(value.get()))
                            }

                            is TInteger -> {
                                append(Token.IntegerToken(value.get()))
                            }

                            is TBoolean -> {
                                append(Token.IntegerToken(if (value.get()) 1L else 0L))
                            }
                        }
                    }
                }
            )
            .toByteArray()
    }

    fun TokenString.toByteArray() = buildByteString {
        tokens.forEach { token ->
            when (token) {
                is Token.ReferenceToken -> {
                    append(idToUuid(token.value))
                }

                is Token.StringToken -> {
                    append(token.value.encodeToByteArray())
                }

                is Token.IntegerToken -> {
                    append(token.value.toByteArray())
                }

                is Token.ByteArrayToken -> {
                    append(token.value)
                }
            }
        }
    }.toByteArray()
}
