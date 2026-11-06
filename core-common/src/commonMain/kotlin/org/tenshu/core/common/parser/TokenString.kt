package org.tenshu.core.common.parser

class TokenString {

    val tokens = mutableListOf<Token>()
    val size: Int
        get() = tokens.sumOf { it.size }
}

fun TokenString.append(token: Token) {
    tokens.add(token)
}

fun TokenString.append(tokenString: TokenString) {
    this.tokens.addAll(tokenString.tokens)
}

fun buildTokenString(block: TokenString.() -> Unit): TokenString {
    return TokenString().apply(block)
}

fun Token.toTokenString(): TokenString {
    return TokenString().apply {
        append(this@toTokenString)
    }

}
