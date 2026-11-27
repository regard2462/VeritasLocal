package com.veritas.localresearch.llm

data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
)

interface EmbeddingTokenizer {
    fun tokenize(text: String, maxLength: Int = 256): TokenizedInput
}
